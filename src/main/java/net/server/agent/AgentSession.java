package net.server.agent;

import client.Character;
import net.PacketHandler;
import net.PacketProcessor;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import net.server.world.World;
import org.slf4j.Logger;
import server.maps.Portal;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long WARP_COOLDOWN_MS = 4000L;
    /** Consecutive portal-walk attempts before giving up and falling back to a party warp. */
    private static final int PORTAL_ATTEMPT_LIMIT = 12;
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
    private volatile long lastWarpAt;
    private volatile int portalTargetMapId = -1;
    private volatile int portalAttempts;
    private final AtomicInteger actionsThisSecond = new AtomicInteger();
    private volatile long actionWindowStart = System.currentTimeMillis();
    /** Local mirror of skill cooldowns, so the agent does not re-issue a still-cooling skill. */
    private final Map<Integer, Long> skillReadyAt = new ConcurrentHashMap<>();

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
        if (followTargetId >= 0 || behaviorEnabled()) {
            actionExecutor.execute(this::behaviorStep);
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
                List<AgentAction> decided = brain.decide(observation, events, memory);
                lastDecisionAt = System.currentTimeMillis();
                // Capture the immutable list in a local: the mutable field may be overwritten by a
                // subsequent decision before the action executor runs.
                List<AgentAction> toApply = decided == null ? List.of() : List.copyOf(decided);
                lastActions = toApply;
                if (toApply.isEmpty()) {
                    return;
                }
                actionExecutor.execute(() -> applyActions(toApply));
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

    /**
     * Runs the server-driven gameplay behaviours each tick: respawn when dead, follow, potion
     * self-sustain, healing, auto-combat, auto-loot and buffing. All of these run on the action
     * executor under a single action-budget token.
     */
    private void behaviorStep() {
        if (!active.get()) {
            return;
        }
        Character p = player;
        if (p == null) {
            return;
        }
        // One budget token covers the whole behaviour tick (follow/sustain/heal/combat/loot/buff).
        if (!allowAction()) {
            return;
        }
        if (!p.isAlive()) {
            if (behaviorEnabled()) {
                runSafely("respawn", () -> AgentCombat.respawn(this));
            }
            return;
        }
        if (followTargetId >= 0 && AgentConfig.followEnabled()) {
            runSafely("follow", this::followStep);
            if (!active.get()) {
                return; // a channel transfer despawned this session mid-step
            }
        }
        if (AgentConfig.autoSustain()) {
            runSafely("sustain", () -> AgentCombat.sustain(this));
        }
        if (AgentConfig.autoHeal()) {
            runSafely("heal", () -> AgentSkill.autoHeal(this));
        }
        if (AgentCombat.shouldFlee(p)) {
            // Retreat instead of trading blows; sustain/heal above still run so it can recover.
            runSafely("flee", () -> AgentCombat.flee(this));
        } else {
            if (AgentConfig.autoCombat()) {
                runSafely("combat", () -> AgentCombat.attackNearest(this));
            }
            if (AgentConfig.autoLoot()) {
                runSafely("loot", () -> AgentCombat.lootNearest(this));
            }
        }
        if (AgentConfig.autoRecharge()) {
            runSafely("recharge", () -> AgentCombat.rechargeAmmo(this));
        }
        if (AgentConfig.autoBuff()) {
            runSafely("buff", () -> AgentSkill.autoCast(this));
        }
    }

    private void runSafely(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log.warn("Agent {} behaviour '{}' failed", getName(), name, t);
        }
    }

    /** Whether any server-driven behaviour needs the per-tick step to run. */
    private static boolean behaviorEnabled() {
        return AgentConfig.autoSustain() || AgentConfig.autoCombat() || AgentConfig.autoBuff()
                || AgentConfig.autoHeal() || AgentConfig.autoLoot() || AgentConfig.autoRecharge()
                || AgentConfig.autoFlee();
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
        if (target != null && target.getMapId() == p.getMapId()) {
            AgentMovement.stepToward(this, p.getPosition(), target.getPosition(), 120);
            return;
        }
        // Not on this map: resolve through the world (target may be on another map or channel).
        World world = p.getWorldServer();
        Character worldTarget = target != null ? target
                : (world == null ? null : world.getPlayerStorage().getCharacterById(targetId));
        if (worldTarget == null || worldTarget.getMap() == null || worldTarget.getClient() == null) {
            return;
        }
        if (worldTarget.getClient().getChannel() != p.getClient().getChannel()) {
            followAcrossChannel(worldTarget);
            return;
        }
        if (!AgentConfig.followWarp()) {
            return;
        }
        // Prefer walking to a portal that leads to the target's map; fall back to a party warp if
        // there is no usable portal, or if it is still unreachable after several attempts.
        Portal portal = AgentMovement.findPortalTo(p, worldTarget.getMapId());
        if (portal != null) {
            if (portalTargetMapId != worldTarget.getMapId()) {
                portalTargetMapId = worldTarget.getMapId();
                portalAttempts = 0;
            }
            portalAttempts++;
            if (portalAttempts <= PORTAL_ATTEMPT_LIMIT) {
                if (AgentMovement.walkThroughPortalTo(this, worldTarget.getMapId())) {
                    lastWarpAt = System.currentTimeMillis();
                }
                return;
            }
            portalTargetMapId = -1; // give up on this portal and warp instead
            portalAttempts = 0;
        }
        warpToFollowTarget(p, targetId);
    }

    /** Re-logs the agent on the follow target's channel (opt-in; throttled in AgentManager). */
    private void followAcrossChannel(Character target) {
        if (!AgentConfig.followChannel()) {
            return;
        }
        // transferChannel despawns this session synchronously; do not touch it afterwards.
        AgentManager.getInstance().transferChannel(getCharacterId(), target.getClient().getChannel());
    }

    /** Warps the agent to its follow target's map (party-warp style) when they leave the map. */
    private void warpToFollowTarget(Character p, int targetId) {
        long now = System.currentTimeMillis();
        if (now - lastWarpAt < WARP_COOLDOWN_MS) {
            return; // avoid ping-ponging while the target is mid-transition
        }
        World world = p.getWorldServer();
        if (world == null) {
            return;
        }
        Character target = world.getPlayerStorage().getCharacterById(targetId);
        if (AgentMovement.warpTo(p, target)) {
            lastWarpAt = now;
        }
    }

    /** Whether the local cooldown for a skill has elapsed (server state is authoritative). */
    public boolean isSkillReady(int skillId) {
        Long readyAt = skillReadyAt.get(skillId);
        return readyAt == null || System.currentTimeMillis() >= readyAt;
    }

    /** Records that a skill was just used and is unavailable for {@code cooldownMs}. */
    public void markSkillUsed(int skillId, long cooldownMs) {
        if (skillId > 0 && cooldownMs > 0) {
            skillReadyAt.put(skillId, System.currentTimeMillis() + cooldownMs);
        }
    }

    /**
     * Sends an inbound packet through the agent's (real) packet processor.
     *
     * @return whether a handler actually ran (false if the packet was dropped by validation)
     */
    public boolean dispatchInbound(RecvOpcode opcode, InPacket packet) {
        PacketProcessor processor = client.getPacketProcessor();
        if (processor == null) {
            return false;
        }
        PacketHandler handler = processor.getHandler((short) opcode.getValue());
        if (handler != null && handler.validateState(client)) {
            handler.handlePacket(packet, client);
            return true;
        }
        return false;
    }

    public void shutdown() {
        active.set(false);
        actionExecutor.shutdown();
    }
}
