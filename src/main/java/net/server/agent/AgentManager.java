package net.server.agent;

import client.Character;
import client.Client;
import net.PacketProcessor;
import net.server.PlayerLoginService;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import service.NoteService;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    private final Map<Integer, AgentSession> sessionsByCharacterId = new ConcurrentHashMap<>();
    private final AtomicLong sessionIdGenerator = new AtomicLong(-5000); // negative to avoid clashing with netty sessions
    private final AgentMemoryStore memoryStore = new AgentMemoryStore();

    private ExecutorService brainPool;
    private ScheduledExecutorService ticker;

    private AgentManager() {
    }

    public static AgentManager getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (ticker != null) {
            return;
        }
        final ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "agent-brain");
            t.setDaemon(true);
            return t;
        };
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
    }

    public synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        for (Integer chrId : new ArrayList<>(sessionsByCharacterId.keySet())) {
            despawn(chrId);
        }
        if (brainPool != null) {
            brainPool.shutdownNow();
            brainPool = null;
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
        if (sessionsByCharacterId.size() >= AgentConfig.maxAgents()) {
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

        final PacketProcessor processor = PacketProcessor.getChannelServerProcessor(world, channel);
        final AgentClient client = new AgentClient(sessionIdGenerator.getAndIncrement(), processor, world, channel);
        client.setAccID(record.accountId());
        client.setAccountName(record.accountName());
        client.setChannel(channel);
        client.setWorld(world);

        try {
            final Character player = Character.loadCharFromDB(record.characterId(), client, true);
            client.setPlayer(player);
            client.updateLoginState(Client.LOGIN_LOGGEDIN);

            final AgentMemory memory = AgentConfig.memoryPersist() ? memoryStore.load(player.getId()) : new AgentMemory(player.getId());
            final AgentSession session = new AgentSession(client, player, brain, memory);
            client.setPacketListener(packet -> {
                // Outbound packets are currently dropped; retained as the seam for future
                // faithful-transcript / packet-decoding perception.
            });

            PlayerLoginService.finishLogin(client, player, true, noteService());

            sessionsByCharacterId.put(player.getId(), session);
            log.info("Spawned agent {} (chr {}, world {}, channel {}) using brain {}",
                    player.getName(), player.getId(), world, channel, brain.describe());
            return session;
        } catch (SQLException e) {
            log.error("Failed to load agent character {}", characterName, e);
            return null;
        }
    }

    public synchronized boolean despawn(int characterId) {
        final AgentSession session = sessionsByCharacterId.remove(characterId);
        if (session == null) {
            return false;
        }
        session.shutdown();
        memoryStore.save(session.getMemory());
        try {
            session.getClient().disconnect(false, false);
        } catch (Throwable t) {
            log.warn("Error while despawning agent {}", session.getName(), t);
        }
        log.info("Despawned agent {}", session.getName());
        return true;
    }

    public boolean despawn(String characterName) {
        for (AgentSession session : sessionsByCharacterId.values()) {
            if (session.getName().equalsIgnoreCase(characterName)) {
                return despawn(session.getCharacterId());
            }
        }
        return false;
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
    // event routing (called by lightweight hooks at chat/party choke points)
    // ------------------------------------------------------------------

    public void onMapChat(MapleMap map, int speakerId, String speakerName, String message, boolean gmChat) {
        if (sessionsByCharacterId.isEmpty()) {
            return;
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
        AgentEvent event = new AgentEvent.PartyChat(-1, speakerName, message);
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
        if (session != null) {
            session.enqueue(new AgentEvent.Whisper(speakerId, speakerName, message));
        }
    }

    public void onPartyInvite(int invitedId, int inviterId, String inviterName, int partyId) {
        AgentSession session = sessionsByCharacterId.get(invitedId);
        if (session != null) {
            session.enqueue(new AgentEvent.PartyInvite(inviterId, inviterName, partyId));
        }
    }

    public void onPartyUpdate(Party party, String description) {
        if (party == null) {
            return;
        }
        for (PartyCharacter member : party.getMembers()) {
            AgentSession session = sessionsByCharacterId.get(member.getId());
            if (session != null) {
                session.enqueue(new AgentEvent.PartyUpdate(description));
            }
        }
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
