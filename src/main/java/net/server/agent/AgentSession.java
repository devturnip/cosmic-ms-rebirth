package net.server.agent;

import client.Character;
import net.PacketHandler;
import net.PacketProcessor;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single live agent: owns its in-process {@link AgentClient} and {@link Character},
 * receives {@link AgentEvent}s, asks its {@link AgentBrain} for decisions and applies the
 * resulting {@link AgentAction}s through the real packet pipeline.
 * <p>
 * Decisions run on a shared brain executor (may block on an LLM call); game actions run on
 * a per-session single-thread executor so an agent's own state changes are serialized.
 */
public class AgentSession {
    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);
    private static final int MAX_INBOX = 64;
    private static final int MAX_EVENTS_PER_DECISION = 16;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final AgentClient client;
    private volatile Character player;
    private final AgentBrain brain;
    private final AgentMemory memory;
    private final AgentActionDispatcher dispatcher = new AgentActionDispatcher();

    private final Queue<AgentEvent> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean deciding = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ExecutorService actionExecutor;

    private volatile long lastDecisionAt;
    private volatile List<AgentAction> lastActions = List.of();
    private volatile int followTargetId = -1;
    private volatile boolean manualFollow = false;
    private final AtomicInteger actionsThisSecond = new AtomicInteger();
    private volatile long actionWindowStart = System.currentTimeMillis();

    public AgentSession(AgentClient client, Character player, AgentBrain brain, AgentMemory memory) {
        this.client = client;
        this.player = player;
        this.brain = brain;
        this.memory = memory;
        final int id = THREAD_COUNTER.incrementAndGet();
        this.actionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-actions-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    public AgentClient getClient() {
        return client;
    }

    public Character getPlayer() {
        return player;
    }

    public AgentMemory getMemory() {
        return memory;
    }

    public String getName() {
        Character p = player;
        return p == null ? "?" : p.getName();
    }

    public int getCharacterId() {
        Character p = player;
        return p == null ? -1 : p.getId();
    }

    public AgentBrain getBrain() {
        return brain;
    }

    public boolean isActive() {
        return active.get();
    }

    public long getLastDecisionAt() {
        return lastDecisionAt;
    }

    public List<AgentAction> getLastActions() {
        return lastActions;
    }

    public int getQueuedEvents() {
        return inbox.size();
    }

    /** Enqueue an event for the agent. Drops the oldest events when the inbox is full. */
    public void enqueue(AgentEvent event) {
        if (!active.get()) {
            return;
        }
        inbox.add(event);
        while (inbox.size() > MAX_INBOX) {
            inbox.poll();
        }
    }

    /**
     * Per-agent action rate limiter, shared by all actions routed through
     * {@link AgentActionDispatcher}.
     *
     * @return whether another action may be performed in the current one-second window
     */
    public synchronized boolean allowAction() {
        long now = System.currentTimeMillis();
        if (now - actionWindowStart >= 1000) {
            actionWindowStart = now;
            actionsThisSecond.set(0);
        }
        return actionsThisSecond.incrementAndGet() <= AgentConfig.maxActionsPerSecond();
    }

    public int getFollowTargetId() {
        return followTargetId;
    }

    /** Sets the follow target managed by party automation (does not override a manual follow). */
    public void setFollowTarget(int characterId) {
        this.followTargetId = characterId;
    }

    /** Sets an explicit follow target that party automation will not override. */
    public void setManualFollow(int characterId) {
        this.manualFollow = true;
        this.followTargetId = characterId;
    }

    public boolean isManualFollow() {
        return manualFollow;
    }

    public void clearFollow() {
        this.followTargetId = -1;
        this.manualFollow = false;
    }

    /**
     * Runs one decision cycle if the agent is idle. Called by {@link AgentManager} on its
     * tick schedule.
     */
    public void tick(ExecutorService brainPool) {
        if (!active.get()) {
            return;
        }
        if (followTargetId >= 0 && AgentConfig.followEnabled()) {
            actionExecutor.execute(this::followStep);
        }
        if (inbox.isEmpty()) {
            return;
        }
        if (!deciding.compareAndSet(false, true)) {
            return; // a decision is already in flight
        }

        final List<AgentEvent> events = drainInbox();
        if (events.isEmpty()) {
            deciding.set(false);
            return;
        }
        recordEvents(events);

        brainPool.execute(() -> {
            try {
                Character p = player;
                if (p == null || !active.get()) {
                    return;
                }
                AgentObservation observation = AgentPerception.observe(p);
                List<AgentAction> actions = brain.decide(observation, events, memory);
                lastDecisionAt = System.currentTimeMillis();
                lastActions = actions == null ? List.of() : List.copyOf(actions);
                if (lastActions.isEmpty()) {
                    return;
                }
                actionExecutor.execute(() -> applyActions(lastActions));
            } catch (Throwable t) {
                log.warn("Agent {} decision failed", getName(), t);
            } finally {
                deciding.set(false);
            }
        });
    }

    private List<AgentEvent> drainInbox() {
        List<AgentEvent> events = new ArrayList<>();
        AgentEvent event;
        while (events.size() < MAX_EVENTS_PER_DECISION && (event = inbox.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    private void recordEvents(List<AgentEvent> events) {
        for (AgentEvent event : events) {
            switch (event) {
                case AgentEvent.MapChat chat -> memory.rememberDialogue(chat.speakerName() + ": " + chat.message());
                case AgentEvent.PartyChat chat -> memory.rememberDialogue("[party] " + chat.speakerName() + ": " + chat.message());
                case AgentEvent.Whisper whisper -> memory.rememberDialogue("[whisper] " + whisper.speakerName() + ": " + whisper.message());
                default -> {
                }
            }
        }
    }

    private void applyActions(List<AgentAction> actions) {
        for (AgentAction action : actions) {
            if (!active.get()) {
                return;
            }
            try {
                dispatcher.dispatch(this, action);
            } catch (Throwable t) {
                log.warn("Agent {} failed to perform {}", getName(), action, t);
            }
        }
    }

    /** Keeps the agent near its follow target (typically the party leader) when on the same map. */
    private void followStep() {
        if (!active.get() || !AgentConfig.followEnabled()) {
            return;
        }
        int targetId = followTargetId;
        Character p = player;
        if (targetId < 0 || p == null || p.getMap() == null) {
            return;
        }
        Character target = p.getMap().getCharacterById(targetId);
        if (target == null || target.getMapId() != p.getMapId()) {
            return;
        }
        if (!allowAction()) {
            return; // follow steps share the agent's action budget
        }
        AgentMovement.stepToward(this, p.getPosition(), target.getPosition(), 120);
    }

    /** Sends an inbound packet through the agent's (real) packet processor. */
    public void dispatchInbound(RecvOpcode opcode, InPacket packet) {
        PacketProcessor processor = client.getPacketProcessor();
        if (processor == null) {
            return;
        }
        PacketHandler handler = processor.getHandler((short) opcode.getValue());
        if (handler != null && handler.validateState(client)) {
            handler.handlePacket(packet, client);
        }
    }

    public void shutdown() {
        active.set(false);
        actionExecutor.shutdown();
    }
}
