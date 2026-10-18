package net.server.agent;

import client.Character;
import client.Client;
import net.PacketProcessor;
import net.server.PlayerLoginService;
import net.server.Server;
import net.server.agent.harness.HarnessBrain;
import net.server.agent.harness.HarnessDialogDecoder;
import net.server.agent.harness.TestHarnessConfig;
import net.server.coordinator.session.SessionCoordinator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import service.NoteService;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the lifecycle of all in-process LLM agents: spawning (which logs a character in
 * through the normal bootstrap), the decision tick schedule, the shared brain executor,
 * and the routing of perceived events to the right {@link AgentSession}.
 */
public class AgentManager {
    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);
    private static final AgentManager INSTANCE = new AgentManager();

    private static final long CHANNEL_TRANSFER_COOLDOWN_MS = 10000L;

    private final Map<Integer, AgentSession> sessionsByCharacterId = new ConcurrentHashMap<>();
    /** Per-character time of the last channel transfer (survives the session swap). */
    private final Map<Integer, Long> lastChannelTransferAt = new ConcurrentHashMap<>();
    private final AtomicLong sessionIdGenerator = new AtomicLong(-5000); // negative to avoid clashing with netty sessions
    private final AgentMemoryStore memoryStore = new AgentMemoryStore();
    private final AgentOptoutStore optoutStore = new AgentOptoutStore();
    /** Deletes ephemeral companion characters when their session ends (GM despawn, shutdown). */
    private volatile AgentProvisioner provisioner = new DbAgentProvisioner();
    /** Characters whose ephemeral row is mid-deletion; {@code spawn} refuses them until it finishes. */
    private final Set<Integer> pendingEphemeralDeletes = ConcurrentHashMap.newKeySet();
    /** Set while {@link #stop()} is tearing down, so a concurrent spawn cannot restart the ticker. */
    private volatile boolean stopping;
    /** Character ids that have opted out of AI-agent interaction (see /agentoptout). */
    private final Set<Integer> optedOut = ConcurrentHashMap.newKeySet();
    /** Serializes the startup opt-out load against concurrent toggles (avoids a load/toggle race). */
    private final Object optoutLock = new Object();

    private ExecutorService brainPool;
    private ScheduledExecutorService ticker;

    private AgentManager() {
    }

    public static AgentManager getInstance() {
        return INSTANCE;
    }

    /**
     * Whether {@code characterId} currently has a live <em>ephemeral</em> agent session
     * (a server-provisioned player companion). Callers use this to keep companions out of
     * contexts that assume a durable participant — notably scripted event instances
     * (party quests), see {@code docs/llm-agent-pq-audit.md}. Test-harness sessions are
     * exempt: they must be able to enter scripted instances to test them.
     */
    public boolean isEphemeralAgent(int characterId) {
        AgentSession session = sessionsByCharacterId.get(characterId);
        return session != null && session.isEphemeral() && !session.isHarness();
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (ticker != null) {
            return;
        }
        stopping = false;
        final ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "agent-brain");
            t.setDaemon(true);
            return t;
        };
        synchronized (optoutLock) {
            optedOut.addAll(optoutStore.loadAll());
        }
        brainPool = Executors.newFixedThreadPool(AgentConfig.maxConcurrentCalls(), daemonFactory);
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-ticker");
            t.setDaemon(true);
            return t;
        });
        long tickMs = AgentConfig.tickMs();
        ticker.scheduleAtFixedRate(this::tickAll, tickMs, tickMs, TimeUnit.MILLISECONDS);
        log.info("AgentManager started (maxAgents={}, tick={}ms, perception={})",
                AgentConfig.maxAgents(), tickMs, AgentConfig.perception());
        // A previous run may have been killed before it could delete its ephemeral companions.
        provisioner.reapOrphans();
        // Warm the cosmetic cash-item cache off the request path (the WZ scan is one-time).
        CompanionAppearance.warmCashCandidatesAsync();
    }

    public void stop() {
        final List<Integer> ids;
        synchronized (this) {
            stopping = true;
            if (ticker != null) {
                ticker.shutdownNow();
                ticker = null;
            }
            ids = new ArrayList<>(sessionsByCharacterId.keySet());
        }
        // Despawn outside the monitor: deleting an ephemeral character does blocking DB I/O and
        // must not stall other agents' spawn/despawn/transfer.
        for (Integer chrId : ids) {
            despawn(chrId);
        }
        synchronized (this) {
            if (brainPool != null) {
                brainPool.shutdownNow();
                brainPool = null;
            }
        }
    }

    private void tickAll() {
        ExecutorService pool = brainPool;
        if (pool == null) {
            return;
        }
        for (AgentSession session : sessionsByCharacterId.values()) {
            try {
                session.tick(pool);
            } catch (Throwable t) {
                log.warn("Agent {} tick failed", session.getName(), t);
            }
        }
    }

    // ------------------------------------------------------------------
    // spawn / despawn
    // ------------------------------------------------------------------

    /**
     * Loads {@code characterName} from the database and brings it online as an agent on the
     * given world/channel.
     *
     * @return the created session, or {@code null} if the character could not be found or
     * the agent limit has been reached.
     */
    public synchronized AgentSession spawn(String characterName, int world, int channel, AgentBrain brain) {
        return spawn(characterName, world, channel, brain, null);
    }

    /**
     * Spawns an agent, optionally reusing an existing in-process {@link AgentMemory} (used by
     * channel transfer so the agent keeps its short-term memory).
     */
    public synchronized AgentSession spawn(String characterName, int world, int channel, AgentBrain brain,
                                           AgentMemory existingMemory) {
        return spawn(characterName, world, channel, brain, existingMemory, false, -1);
    }

    /**
     * Spawns a session that may be an ephemeral player companion. Ephemeral sessions never load
     * or persist {@link AgentMemory} and record the requesting player for the per-player cap and
     * {@code /agents dismiss}.
     *
     * @param ephemeral whether the character is a server-provisioned, disposable companion
     * @param ownerPlayerId the requesting player's character id, or -1 for operator spawns
     */
    public synchronized AgentSession spawn(String characterName, int world, int channel, AgentBrain brain,
                                           AgentMemory existingMemory, boolean ephemeral, int ownerPlayerId) {
        return spawn(characterName, world, channel, brain, existingMemory, ephemeral, ownerPlayerId, null);
    }

    /**
     * Spawns a session carrying the provisioning record of an ephemeral companion, so the
     * character can be deleted when the session ends.
     */
    public synchronized AgentSession spawn(String characterName, int world, int channel, AgentBrain brain,
                                           AgentMemory existingMemory, boolean ephemeral, int ownerPlayerId,
                                           AgentProvisioner.ProvisionedAgent provisioned) {
        return spawn(characterName, world, channel, brain, existingMemory, ephemeral, ownerPlayerId, provisioned, false);
    }

    /**
     * Spawns a <em>test-harness</em> session: an ephemeral character driven externally over the
     * test-harness MCP surface instead of by an autonomous brain. Harness sessions are gated by
     * {@code USE_TEST_HARNESS} and capped by {@code TEST_HARNESS_MAX_SESSIONS} — they never
     * consume {@code LLM_AGENTS_MAX} slots, require {@code USE_LLM_AGENTS}, or run autonomous
     * behaviour ticks. The session loots normally (unlike companions) so item-drop tests work.
     *
     * @return the created session, or {@code null} if the harness is disabled, a cap is reached,
     *         or the login failed
     */
    public synchronized AgentSession spawnHarness(String characterName, int world, int channel,
                                                  AgentProvisioner.ProvisionedAgent provisioned) {
        return spawn(characterName, world, channel, new HarnessBrain(), null, true, -1, provisioned, true);
    }

    private synchronized AgentSession spawn(String characterName, int world, int channel, AgentBrain brain,
                                            AgentMemory existingMemory, boolean ephemeral, int ownerPlayerId,
                                            AgentProvisioner.ProvisionedAgent provisioned, boolean harness) {
        if (harness ? !TestHarnessConfig.enabled() : !AgentConfig.enabled()) {
            log.warn("Refusing to spawn agent {}: {} is disabled", characterName,
                    harness ? "USE_TEST_HARNESS" : "USE_LLM_AGENTS");
            return null;
        }
        if (stopping) {
            log.warn("Refusing to spawn agent {}: AgentManager is stopping", characterName);
            return null;
        }
        if (ticker == null) {
            start(); // defensive: ensure the tick loop/pool exist even if server auto-start was skipped
        }
        if (harness) {
            long harnessSessions = sessionsByCharacterId.values().stream().filter(AgentSession::isHarness).count();
            if (harnessSessions >= TestHarnessConfig.maxSessions()) {
                log.warn("Refusing to spawn harness agent {}: harness session limit ({}) reached",
                        characterName, TestHarnessConfig.maxSessions());
                return null;
            }
        } else if (sessionsByCharacterId.size() >= AgentConfig.maxAgents()) {
            log.warn("Refusing to spawn agent {}: agent limit ({}) reached", characterName, AgentConfig.maxAgents());
            return null;
        }

        final CharacterRecord record = loadCharacterRecord(characterName, world);
        if (record == null) {
            log.warn("Unable to spawn agent {}: no such character in world {}", characterName, world);
            return null;
        }
        if (sessionsByCharacterId.containsKey(record.characterId())) {
            log.warn("Character {} is already an active agent", characterName);
            return null;
        }
        if (pendingEphemeralDeletes.contains(record.characterId())) {
            log.warn("Refusing to spawn agent {}: its character is being deleted", characterName);
            return null;
        }

        // Never hijack a character/account that a real player is currently using: doing so
        // would force-disconnect them via SessionCoordinator.updateOnlineClient.
        final World worldServer = Server.getInstance().getWorld(world);
        if (worldServer == null) {
            log.warn("Refusing to spawn agent {}: world {} does not exist", characterName, world);
            return null;
        }
        if (worldServer.getPlayerStorage().getCharacterById(record.characterId()) != null) {
            log.warn("Refusing to spawn agent {}: character is already online", characterName);
            return null;
        }
        if (SessionCoordinator.getInstance().isAccountOnline(record.accountId())) {
            log.warn("Refusing to spawn agent {}: account {} already has an online session", characterName, record.accountId());
            return null;
        }

        if (!harness && !hasAgentPrefix(characterName)) {
            if (AgentConfig.requireNamePrefix()) {
                log.warn("Refusing to spawn agent {}: name does not start with the label prefix '{}' or companion prefix '{}'",
                        characterName, AgentConfig.namePrefix(), AgentConfig.companionNamePrefix());
                return null;
            }
            log.warn("Agent character '{}' does not start with the label prefix '{}' or companion prefix '{}'; agents should be distinguishable to players",
                    characterName, AgentConfig.namePrefix(), AgentConfig.companionNamePrefix());
        }

        final PacketProcessor processor = PacketProcessor.getChannelServerProcessor(world, channel);
        final AgentClient client = new AgentClient(sessionIdGenerator.getAndIncrement(), processor, world, channel);
        client.setAccID(record.accountId());
        client.setAccountName(record.accountName());
        client.setChannel(channel);
        client.setWorld(world);

        try {
            final Character player = Character.loadCharFromDB(record.characterId(), client, true);
            client.setPlayer(player);
            // Atomically claim the account for the agent. This closes the TOCTOU window between
            // the online checks above and the (slow) DB load: if a real player logged in in the
            // meantime, we refuse instead of force-disconnecting them in updateLoginState.
            if (!SessionCoordinator.getInstance().registerAgentClientIfAbsent(client)) {
                log.warn("Refusing to spawn agent {}: account {} came online during spawn", characterName, record.accountId());
                client.forceDisconnect();
                return null;
            }
            client.updateLoginState(Client.LOGIN_LOGGEDIN);

            final AgentMemory memory = existingMemory != null ? existingMemory
                    : (harness ? new AgentMemory(player.getId())
                       : ((!ephemeral && AgentConfig.memoryPersist()) ? memoryStore.load(player.getId()) : new AgentMemory(player.getId())));
            final AgentSession session = new AgentSession(client, player, brain, memory, ephemeral, ownerPlayerId, harness);
            session.setProvisioned(provisioned);
            client.setPacketListener(packet -> {
                // Harness sessions decode NPC dialog frames so `observe` can show the pending
                // dialog; all other outbound packets (and non-harness agents) are dropped.
                if (harness) {
                    HarnessDialogDecoder.process(session, packet);
                }
            });

            PlayerLoginService.finishLogin(client, player, true, noteService());
            // Real clients reset this by sending PLAYER_MAP_TRANSFER; an agent never does, so
            // without this the agent would look permanently "changing maps" and cross-map
            // follow (and any transition guard) would never fire.
            player.setMapTransitionComplete();

            if (AgentConfig.notice() && !harness && player.getMap() != null) {
                player.getMap().broadcastMessage(PacketCreator.serverNotice(6,
                        player.getName() + " is an AI-controlled agent."));
            }

            sessionsByCharacterId.put(player.getId(), session);
            log.info("Spawned agent {} (chr {}, world {}, channel {}) using brain {}{}",
                    player.getName(), player.getId(), world, channel, brain.describe(), harness ? " [harness]" : "");
            return session;
        } catch (Exception e) {
            log.error("Failed to spawn agent {}; rolling back partial login", characterName, e);
            try {
                client.forceDisconnect();
            } catch (Throwable t) {
                log.warn("Failed to roll back agent spawn for {}", characterName, t);
            }
            return null;
        }
    }

    /**
     * Moves an agent to another channel by re-running its headless login there. The old session
     * is despawned synchronously first (avoiding a despawn/respawn race) and the new one reuses
     * the same brain and memory; the caller is responsible for restoring follow behaviour.
     *
     * @return the new session, or {@code null} if the transfer failed
     */
    public synchronized AgentSession transferChannel(int characterId, int channel) {
        final AgentSession old = sessionsByCharacterId.get(characterId);
        if (old == null || channel <= 0) {
            return null;
        }
        if (old.getClient().getChannel() == channel) {
            return old;
        }
        final Character oldPlayer = old.getPlayer();
        if (oldPlayer == null || oldPlayer.isChangingMaps()) {
            return null; // never yank a character mid-transition
        }
        final long now = System.currentTimeMillis();
        final Long lastTransfer = lastChannelTransferAt.get(characterId);
        if (lastTransfer != null && now - lastTransfer < CHANNEL_TRANSFER_COOLDOWN_MS) {
            return null; // throttled
        }
        lastChannelTransferAt.put(characterId, now);

        final String name = old.getName();
        final AgentBrain brain = old.getBrain();
        final AgentMemory memory = old.getMemory();
        final int world = old.getClient().getWorld();
        final int oldChannel = old.getClient().getChannel();
        final int followTarget = old.getFollowTargetId();
        final boolean manualFollow = old.isManualFollow();
        final boolean muted = old.isMuted();
        final boolean paused = old.isPaused();
        final boolean ephemeral = old.isEphemeral();
        final int ownerPlayerId = old.getOwnerPlayerId();
        final AgentProvisioner.ProvisionedAgent provisioned = old.getProvisioned();

        // Remove without deleting the ephemeral character: it is re-spawned on the new channel.
        removeSession(characterId, false);
        AgentSession session = spawn(name, world, channel, brain, memory, ephemeral, ownerPlayerId, provisioned);
        if (session == null) {
            // A failed transfer must never silently delete the agent: re-spawn on the old channel.
            log.warn("Channel transfer of agent {} to channel {} failed; rolling back to channel {}", name, channel, oldChannel);
            session = spawn(name, world, oldChannel, brain, memory, ephemeral, ownerPlayerId, provisioned);
            if (session == null) {
                log.error("Rollback of agent {} to channel {} also failed; agent is offline", name, oldChannel);
                lastChannelTransferAt.remove(characterId);
                destroyIfEphemeral(ephemeral, provisioned);
                return null;
            }
        }
        session.setMuted(muted); // moderation must survive a channel transfer
        session.setPaused(paused); // pause must survive a channel transfer
        if (followTarget >= 0) {
            if (manualFollow) {
                session.setManualFollow(followTarget);
            } else {
                session.setFollowTarget(followTarget);
            }
        }
        log.info("Transferred agent {} to channel {}", name, channel);
        return session;
    }

    public boolean despawn(int characterId) {
        final AgentSession session = removeSession(characterId, true);
        if (session == null) {
            return false;
        }
        // Delete outside the monitor: the character cascade is blocking DB I/O. The pending marker
        // set in removeSession makes a concurrent spawn of the same character refuse, so the row
        // can never be deleted out from under a freshly re-spawned session.
        try {
            destroyIfEphemeral(session.isEphemeral(), session.getProvisioned());
        } finally {
            pendingEphemeralDeletes.remove(characterId);
        }
        return true;
    }

    /**
     * Removes and disconnects a session <em>without</em> deleting an ephemeral character. Used by
     * channel transfer, which re-spawns the same character and must not delete it.
     *
     * @param markDestruction when true, tombstone the character id so a concurrent spawn refuses it
     *                        until {@link #despawn(int)} finishes the delete
     */
    private synchronized AgentSession removeSession(int characterId, boolean markDestruction) {
        final AgentSession session = sessionsByCharacterId.remove(characterId);
        if (session == null) {
            return null;
        }
        if (markDestruction && session.isEphemeral() && session.getProvisioned() != null) {
            pendingEphemeralDeletes.add(characterId);
        }
        session.shutdown();
        // Persist and disconnect outside any monitor: both can block on DB I/O, and the map
        // removal above is already atomic on the concurrent map.
        if (!session.isEphemeral() && AgentConfig.memoryPersist()) {
            memoryStore.save(session.getMemory());
        }
        try {
            session.getClient().forceDisconnect();
        } catch (Throwable t) {
            log.warn("Error while despawning agent {}", session.getName(), t);
        }
        log.info("Despawned agent {}", session.getName());
        return session;
    }

    /** Best-effort deletion of an ephemeral companion character (never throws). */
    private void destroyIfEphemeral(boolean ephemeral, AgentProvisioner.ProvisionedAgent provisioned) {
        if (!ephemeral || provisioned == null) {
            return;
        }
        try {
            provisioner.destroy(provisioned);
        } catch (Throwable t) {
            log.warn("Failed to delete ephemeral companion {}", provisioned.name(), t);
        }
    }

    /** Test seam: replaces the provisioner used to delete ephemeral characters on despawn. */
    void setProvisioner(AgentProvisioner provisioner) {
        if (provisioner != null) {
            this.provisioner = provisioner;
        }
    }

    public boolean despawn(String characterName) {
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getName().equalsIgnoreCase(characterName)) {
                return despawn(session.getCharacterId());
            }
        }
        return false;
    }

    /** Number of live agent sessions summoned by {@code ownerPlayerId} (player companions). */
    public int countOwnedBy(int ownerPlayerId) {
        if (ownerPlayerId < 0) {
            return 0;
        }
        int count = 0;
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getOwnerPlayerId() == ownerPlayerId) {
                count++;
            }
        }
        return count;
    }

    /**
     * Pauses or resumes every live session summoned by {@code ownerPlayerId}.
     *
     * @return the number of owned sessions whose pause state actually changed
     */
    /** Suspends/resumes every live companion owned by a player; synchronized with transfers. */
    public synchronized int setPausedOwnedBy(int ownerPlayerId, boolean paused) {
        if (ownerPlayerId < 0) {
            return 0;
        }
        int changed = 0;
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getOwnerPlayerId() == ownerPlayerId && session.isPaused() != paused) {
                session.setPaused(paused);
                changed++;
            }
        }
        return changed;
    }

    /** Number of live sessions summoned by {@code ownerPlayerId} that are currently paused. */
    /** How many live companions owned by a player are paused. */
    public synchronized int pausedOwnedBy(int ownerPlayerId) {
        if (ownerPlayerId < 0) {
            return 0;
        }
        int count = 0;
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getOwnerPlayerId() == ownerPlayerId && session.isPaused()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Despawns every live session summoned by {@code ownerPlayerId}.
     *
     * @return the sessions that were despawned (for the caller to delete their characters)
     */
    public List<AgentSession> despawnOwnedBy(int ownerPlayerId) {
        if (ownerPlayerId < 0) {
            return List.of();
        }
        List<AgentSession> owned = new ArrayList<>();
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getOwnerPlayerId() == ownerPlayerId) {
                owned.add(session);
            }
        }
        for (AgentSession session : owned) {
            despawn(session.getCharacterId());
        }
        return owned;
    }

    public Collection<AgentSession> getSessions() {
        return List.copyOf(sessionsByCharacterId.values());
    }

    public AgentSession getSession(int characterId) {
        return sessionsByCharacterId.get(characterId);
    }

    public boolean isAgent(int characterId) {
        return sessionsByCharacterId.containsKey(characterId);
    }

    public boolean isAgentName(String name) {
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // player opt-out
    // ------------------------------------------------------------------

    /** Whether {@code characterId} has opted out of all AI-agent interaction. */
    public boolean isOptedOut(int characterId) {
        return optedOut.contains(characterId);
    }

    /** Sets the opt-out state for a player and persists it (best-effort; DB errors are logged). */
    public void setOptedOut(int characterId, boolean value) {
        synchronized (optoutLock) {
            applyOptedOut(characterId, value);
        }
        optoutStore.set(characterId, value);
    }

    /** In-memory opt-out update without persistence (also a test seam). */
    void applyOptedOut(int characterId, boolean value) {
        if (value) {
            optedOut.add(characterId);
        } else {
            optedOut.remove(characterId);
        }
    }

    // ------------------------------------------------------------------
    // event routing (called by lightweight hooks at chat/party choke points)
    // ------------------------------------------------------------------

    public void onMapChat(MapleMap map, int speakerId, String speakerName, String message, boolean gmChat) {
        if (sessionsByCharacterId.isEmpty() || isOptedOut(speakerId)) {
            return; // an opted-out player is invisible to agents
        }
        AgentEvent event = new AgentEvent.MapChat(speakerId, speakerName, message, gmChat);
        for (AgentSession session : sessionsByCharacterId.values()) {
            Character player = session.getPlayer();
            if (player != null && player.getId() != speakerId && player.getMap() == map) {
                session.enqueue(event);
            }
        }
    }

    public void onPartyChat(Party party, String speakerName, String message) {
        if (party == null || sessionsByCharacterId.isEmpty()) {
            return;
        }
        int speakerId = -1;
        for (PartyCharacter member : party.getMembers()) {
            if (member.getName().equals(speakerName)) {
                speakerId = member.getId();
                break;
            }
        }
        if (isOptedOut(speakerId)) {
            return; // an opted-out party member is invisible to agents
        }
        AgentEvent event = new AgentEvent.PartyChat(speakerId, speakerName, message);
        for (PartyCharacter member : party.getMembers()) {
            if (member.getName().equals(speakerName)) {
                continue;
            }
            AgentSession session = sessionsByCharacterId.get(member.getId());
            if (session != null) {
                session.enqueue(event);
            }
        }
    }

    public void onWhisper(int targetId, int speakerId, String speakerName, String message) {
        AgentSession session = sessionsByCharacterId.get(targetId);
        if (session != null && !isOptedOut(speakerId)) {
            session.enqueue(new AgentEvent.Whisper(speakerId, speakerName, message));
        }
    }

    public void onPartyInvite(int invitedId, int inviterId, String inviterName, int partyId) {
        AgentSession session = sessionsByCharacterId.get(invitedId);
        if (session != null && !isOptedOut(inviterId)) {
            session.enqueue(new AgentEvent.PartyInvite(inviterId, inviterName, partyId));
        }
    }

    public void onPartyUpdate(Party party, String description) {
        if (sessionsByCharacterId.isEmpty() || party == null) {
            return;
        }
        for (PartyCharacter member : party.getMembers()) {
            AgentSession session = sessionsByCharacterId.get(member.getId());
            if (session != null) {
                session.enqueue(new AgentEvent.PartyUpdate(description));
            }
        }
    }

    /**
     * Recomputes automatic follow targets from the agents' current party state. Invoked after
     * a party operation has been applied, so it sees the post-mutation leader and roster.
     */
    public void refreshAutoFollow() {
        if (sessionsByCharacterId.isEmpty()) {
            return;
        }
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.isManualFollow()) {
                continue;
            }
            Character p = session.getPlayer();
            if (p == null) {
                continue;
            }
            Party party = p.getParty();
            if (party == null || party.getMemberById(p.getId()) == null) {
                session.clearFollow();
                continue;
            }
            int leaderId = party.getLeaderId();
            if (leaderId != p.getId() && !isOptedOut(leaderId)) {
                session.setFollowTarget(leaderId);
            } else {
                session.clearFollow();
            }
        }
    }

    /**
     * Registers an agent as a party-search leader so solo players can find it through the
     * in-game party search UI. Creates a party for the agent if it does not have one.
     *
     * @return whether the agent is now registered
     */
    public boolean registerForPartySearch(AgentSession session, int minLevel, int maxLevel, int jobs) {
        Character player = session.getPlayer();
        if (player == null || player.getMap() == null || player.getLevel() < 10) {
            return false;
        }
        World world = Server.getInstance().getWorld(player.getWorld());
        if (world == null) {
            return false;
        }
        if (player.getParty() == null) {
            Party.createParty(player, false);
        }
        if (player.getParty() == null || !player.isPartyLeader()) {
            return false;
        }
        world.getPartySearchCoordinator().registerPartyLeader(player, minLevel, maxLevel, jobs);
        return true;
    }

    /** Registers a named agent for party search over a +/- level window around its level. */
    public boolean registerForPartySearch(String agentName, int worldId) {
        AgentSession session = findByName(agentName);
        if (session == null || session.getPlayer() == null) {
            return false;
        }
        Character player = session.getPlayer();
        if (player.getWorld() != worldId || player.getMap() == null || player.getLevel() < 10) {
            return false;
        }
        int level = player.getLevel();
        return registerForPartySearch(session, Math.max(10, level - 10), level + 10, Integer.MAX_VALUE);
    }

    /** Points an agent's follow target at a player in the given world. */
    public boolean setFollow(String agentName, String targetName, int worldId) {
        AgentSession session = findByName(agentName);
        World world = Server.getInstance().getWorld(worldId);
        if (session == null || world == null) {
            return false;
        }
        Character target = world.getPlayerStorage().getCharacterByName(targetName);
        if (target == null || isOptedOut(target.getId())) {
            return false;
        }
        session.setManualFollow(target.getId());
        return true;
    }

    public boolean clearFollow(String agentName) {
        AgentSession session = findByName(agentName);
        if (session == null) {
            return false;
        }
        session.clearFollow();
        return true;
    }

    private AgentSession findByName(String agentName) {
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getName().equalsIgnoreCase(agentName)) {
                return session;
            }
        }
        return null;
    }

    public void onMapChanged(Character player, int mapId) {
        AgentSession session = sessionsByCharacterId.get(player.getId());
        if (session != null) {
            session.enqueue(new AgentEvent.MapChanged(mapId));
        }
    }

    /** Delivers an out-of-band instruction (e.g. from a GM command) to a named agent. */
    public boolean instruct(String characterName, String message) {
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getName().equalsIgnoreCase(characterName)) {
                session.enqueue(new AgentEvent.Instruction(message));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private NoteService noteService() {
        return Server.getInstance().getChannelDependencies().noteService();
    }

    /**
     * Whether {@code name} carries the operator label prefix or the player-companion prefix.
     * Player companions are provisioned as {@code Agent-<word>}, so spawn must accept both.
     */
    static boolean hasAgentPrefix(String name) {
        return name != null
                && (name.startsWith(AgentConfig.namePrefix())
                || name.startsWith(AgentConfig.companionNamePrefix()));
    }

    private CharacterRecord loadCharacterRecord(String characterName, int world) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT c.id, c.accountid, a.name FROM characters c JOIN accounts a ON a.id = c.accountid " +
                             "WHERE c.name = ? AND c.world = ?")) {
            ps.setString(1, characterName);
            ps.setInt(2, world);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CharacterRecord(rs.getInt("id"), rs.getInt("accountid"), rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to look up agent character {}", characterName, e);
        }
        return null;
    }

    private record CharacterRecord(int characterId, int accountId, String accountName) {
    }
}
