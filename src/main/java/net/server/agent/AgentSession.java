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
    /** Whether this session is an ephemeral player companion (memory is not persisted; dismiss deletes). */
    private final boolean ephemeral;
    /** Character id of the player who requested this companion, or -1 for GM/operator spawns. */
    private final int ownerPlayerId;
    /** For ephemeral companions: the provisioning record used to delete the character on despawn. */
    private volatile AgentProvisioner.ProvisionedAgent provisioned;
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
    /** Last time each player was greeted by proactive behavior. */
    private final Map<Integer, Long> greetedAt = new ConcurrentHashMap<>();
    private volatile long lastMatchmakeAt;
    private volatile long lastPatrolAt;
    /** When muted, the agent's chat actions are dropped (GM moderation). */
    private volatile boolean muted;
    /** Whether the last emitted movement step was a rope/ladder climb (drives pathing classification). */
    private volatile boolean climbingRope;
    /** Whether a movement step ran in the current behaviour tick (drives idle-stance sync). */
    private volatile boolean movedThisTick;
    /** When paused, the agent skips all decisions and server-driven behaviour until resumed. */
    private volatile boolean paused;

    public AgentSession(AgentClient client, Character player, AgentBrain brain, AgentMemory memory) {
        this(client, player, brain, memory, false, -1);
    }

    public AgentSession(AgentClient client, Character player, AgentBrain brain, AgentMemory memory,
                        boolean ephemeral, int ownerPlayerId) {
        this.client = client;
        this.player = player;
        this.brain = brain;
        this.memory = memory;
        this.ephemeral = ephemeral;
        this.ownerPlayerId = ownerPlayerId;
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

    /** Whether this session is an ephemeral, player-summoned companion. */
    public boolean isEphemeral() {
        return ephemeral;
    }

    /** Character id of the player who summoned this companion, or -1 for operator spawns. */
    public int getOwnerPlayerId() {
        return ownerPlayerId;
    }

    /** The provisioning record for an ephemeral companion, or {@code null} for persistent agents. */
    public AgentProvisioner.ProvisionedAgent getProvisioned() {
        return provisioned;
    }

    /** Records the provisioning data so {@code AgentManager} can delete the character on despawn. */
    public void setProvisioned(AgentProvisioner.ProvisionedAgent provisioned) {
        this.provisioned = provisioned;
    }

    public boolean isActive() {
        return active.get();
    }

    public boolean isMuted() {
        return muted;
    }

    /** Sets chat suppression (idempotent; single volatile write so concurrent GMs cannot race). */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Whether the agent's last movement step was a rope/ladder climb step. */
    public boolean isClimbingRope() {
        return climbingRope;
    }

    public void setClimbingRope(boolean climbingRope) {
        this.climbingRope = climbingRope;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Suspends/resumes an agent (idempotent; single volatile write so concurrent toggles cannot race). */
    public void setPaused(boolean paused) {
        this.paused = paused;
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

    /** Records that a movement step ran, so the tick will not overwrite it with an idle stance. */
    public void markMoved() {
        movedThisTick = true;
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
        if (!active.get() || paused) {
            // Paused: do not schedule behaviour or drain/decide the inbox. Queued events survive
            // the pause and are handled on resume.
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
        if (AgentConfig.memorySummarize()) {
            memory.compact();
        }
    }

    private void applyActions(List<AgentAction> actions) {
        if (paused) {
            return; // drop a decision that was already in flight when the agent was paused
        }
        for (AgentAction action : actions) {
            if (!active.get() || paused) {
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
        if (!active.get() || paused) {
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
        movedThisTick = false;
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
            if (!isEphemeral() && AgentConfig.autoLoot()) {
                runSafely("loot", () -> AgentCombat.lootNearest(this));
            }
        }
        if (AgentConfig.autoRecharge()) {
            runSafely("recharge", () -> AgentCombat.rechargeAmmo(this));
        }
        if (AgentConfig.autoBuff()) {
            runSafely("buff", () -> AgentSkill.autoCast(this));
        }
        if (AgentConfig.proactive()) {
            runSafely("proactive", this::proactiveStep);
        }
        if (!movedThisTick) {
            // No movement this tick: tell clients the agent is idle so the last walk animation ends.
            AgentMovement.broadcastIdle(this);
        }
    }

    /** Runs the opt-in proactive behaviors (greet / matchmake / patrol), each self-throttled. */
    private void proactiveStep() {
        if (!active.get()) {
            return;
        }
        Character p = player;
        if (p == null || !p.isAlive()) {
            return;
        }
        if (AgentConfig.greet()) {
            runSafely("greet", () -> AgentProactivity.greet(this));
        }
        if (AgentConfig.matchmake()) {
            runSafely("matchmake", () -> AgentProactivity.matchmake(this));
        }
        if (AgentConfig.patrol()) {
            runSafely("patrol", () -> AgentProactivity.patrol(this));
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
                || AgentConfig.autoFlee()
                || AgentConfig.greet() || AgentConfig.matchmake() || AgentConfig.patrol();
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
            AgentMovement.stepToward(this, p.getPosition(), target.getPosition(), AgentMovement.walkStepPerTick());
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

    // Throttle timestamps use the monotonic clock, so a wall-clock adjustment cannot stall them.

    /** Read-only check: whether {@code playerId} is due for a greeting (does not mark). */
    public boolean isGreetDue(int playerId, long cooldownMs) {
        Long last = greetedAt.get(playerId);
        return last == null || System.nanoTime() - last >= millisToNanos(cooldownMs);
    }

    /** Records that {@code playerId} was successfully greeted. */
    public void markGreeted(int playerId) {
        greetedAt.put(playerId, System.nanoTime());
    }

    /** Drops greeting timestamps older than the cooldown so the map cannot grow unbounded. */
    public void pruneGreetings(long cooldownMs) {
        long cutoff = System.nanoTime() - millisToNanos(cooldownMs);
        greetedAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /** Throttle gate for party-search (re)registration. */
    public boolean markMatchmakeDue(long intervalMs) {
        long now = System.nanoTime();
        if (now - lastMatchmakeAt < millisToNanos(intervalMs)) {
            return false;
        }
        lastMatchmakeAt = now;
        return true;
    }

    /** Throttle gate for patrol steps. */
    public boolean markPatrolDue(long intervalMs) {
        long now = System.nanoTime();
        if (now - lastPatrolAt < millisToNanos(intervalMs)) {
            return false;
        }
        lastPatrolAt = now;
        return true;
    }

    private static long millisToNanos(long millis) {
        return Math.max(0L, millis) * 1_000_000L;
    }

    /**
     * Executes an action directly (used by server-driven proactive behavior).
     *
     * @return whether the action was actually executed (false if muted/rate-limited)
     */
    public boolean perform(AgentAction action) {
        if (paused) {
            return false; // no server-driven behaviour while paused
        }
        try {
            return dispatcher.dispatch(this, action);
        } catch (Throwable t) {
            log.warn("Agent {} failed to perform proactive {}", getName(), action, t);
            return false;
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
