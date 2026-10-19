package net.server.agent.harness;

import client.Job;
import net.server.Server;
import net.server.agent.AgentAction;
import net.server.agent.AgentEvent;
import net.server.agent.AgentManager;
import net.server.agent.AgentProvisioner;
import net.server.agent.AgentSession;
import net.server.agent.DbAgentProvisioner;
import net.server.agent.harness.HarnessVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates test-harness sessions: provisioning ephemeral characters, driving their
 * actions, observing their state and tearing them down. All session characters are
 * server-owned and deleted on {@link #endSession}, and anything left behind by a crashed
 * run is removed by the provisioner's startup reaper.
 * <p>
 * This is the backend behind the harness MCP tools; it is deliberately independent of the
 * transport (stdio or HTTP) so unit tests can drive it directly.
 */
public final class TestHarnessService {
    private static final Logger log = LoggerFactory.getLogger(TestHarnessService.class);
    private static final TestHarnessService INSTANCE = new TestHarnessService();

    public static TestHarnessService getInstance() {
        return INSTANCE;
    }

    private final AgentProvisioner provisioner = new DbAgentProvisioner();
    /** characterId -> the live harness session. */
    private final Map<Integer, AgentSession> sessions = new ConcurrentHashMap<>();

    private TestHarnessService() {
    }

    private void requireEnabled() {
        if (!TestHarnessConfig.enabled()) {
            throw new IllegalStateException("Test harness is disabled (USE_TEST_HARNESS: false)");
        }
    }

    private AgentSession session(int characterId) {
        requireEnabled();
        AgentSession session = sessions.get(characterId);
        if (session == null) {
            throw new IllegalArgumentException("No harness session with id " + characterId
                    + " (it may have ended; list ids via session_list)");
        }
        return session;
    }

    /**
     * Provisions an ephemeral character and spawns it as a harness session.
     *
     * @param jobId  job id, or 0 for a plain Beginner
     * @param level  1..200
     * @param mapId  spawn map, or 0 for the job's default (map 0 / beginner map)
     * @param world  world id (0 on single-world servers)
     * @return observation of the freshly spawned session
     */
    public Map<String, Object> startSession(int jobId, int level, int mapId, int world) {
        requireEnabled();
        if (world < 0 || Server.getInstance().getWorld(world) == null) {
            throw new IllegalArgumentException("World " + world + " does not exist on this server");
        }
        int safeLevel = Math.max(1, Math.min(level, 200));
        Job job = jobId > 0 ? Job.getById(jobId) : Job.BEGINNER;
        if (job == null) {
            throw new IllegalArgumentException("Unknown job id " + jobId);
        }
        AgentProvisioner.ProvisionedAgent provisioned = provisioner.provision(safeLevel, job, mapId, world);
        if (provisioned == null || !provisioned.isValid()) {
            throw new IllegalStateException("Provisioning failed (DB error, name collision or world unavailable)");
        }
        AgentSession session = AgentManager.getInstance().spawnHarness(provisioned.name(), world, 1, provisioned);
        if (session == null) {
            // Provisioning succeeded but login failed: clean up the row so nothing leaks.
            provisioner.destroy(provisioned);
            throw new IllegalStateException("Spawn failed (harness cap reached, world/channel unavailable or login error)");
        }
        sessions.put(session.getCharacterId(), session);
        log.info("Harness session {} started ({} level {} job {})", session.getCharacterId(),
                provisioned.name(), safeLevel, job);
        return HarnessObservation.describe(session, List.of());
    }

    /** Despawns the session and deletes its character + account (idempotent). */
    public Map<String, Object> endSession(int characterId) {
        requireEnabled();
        AgentSession session = sessions.remove(characterId);
        if (session == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "session " + characterId + " already ended");
            return out;
        }
        boolean despawned = AgentManager.getInstance().despawn(characterId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("despawned", despawned);
        out.put("message", "session " + characterId + " ended; character and account deleted");
        return out;
    }

    /** Lists live harness session ids. */
    public Map<String, Object> listSessions() {
        requireEnabled();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AgentSession session : sessions.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sessionId", session.getCharacterId());
            e.put("name", session.getName());
            entries.add(e);
        }
        out.put("sessions", entries);
        return out;
    }

    /** Exposes a live harness session (used by the MCP layer for setup ops). */
    public AgentSession getSession(int characterId) {
        return session(characterId);
    }

    /** Full structured observation, including any harness events buffered since the last call. */
    public Map<String, Object> observe(int characterId) {
        return observe(characterId, false);
    }

    /** Observation with an optional whole-map (OMNISCIENT) range override. */
    public Map<String, Object> observe(int characterId, boolean omniscient) {
        AgentSession session = session(characterId);
        if (session.getBrain() instanceof HarnessBrain brain) {
            return HarnessObservation.describe(session, brain.drainEvents(), omniscient);
        }
        return HarnessObservation.describe(session, List.of(), omniscient);
    }

    /** Drains only the buffered events (chat, map changes, party invites...). */
    public Map<String, Object> drainEvents(int characterId) {
        AgentSession session = session(characterId);
        List<Object> events = new ArrayList<>();
        if (session.getBrain() instanceof HarnessBrain brain) {
            for (AgentEvent event : brain.drainEvents()) {
                events.add(event.toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        return out;
    }

    /**
     * Submits actions through the real packet pipeline. Blocks until the server has applied
     * every action; returns per-action {@code dispatched} (packet sent through the real
     * handlers) and {@code accepted} (server actually applied it; null = not observable).
     */
    public Map<String, Object> act(int characterId, List<AgentAction> actions) {
        AgentSession session = session(characterId);
        List<HarnessVerifier.Result> results = session.applyExternalActions(actions);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HarnessVerifier.Result r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dispatched", r.dispatched());
            row.put("accepted", r.accepted());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", rows);
        out.put("observation", HarnessObservation.describe(session, List.of()));
        return out;
    }

    /** Dispatches a setup (cheat) op by name. */
    public Map<String, Object> setup(String op, AgentSession session, Map<String, Object> args) {
        requireEnabled();
        return switch (op == null ? "" : op.strip().toLowerCase()) {
            case "teleport" -> TestOps.teleport(session, intArg(args, "mapId"), strArg(args, "portal"));
            case "spawn_mob" -> TestOps.spawnMob(session, intArg(args, "mobId"),
                    intArg(args, "x"), intArg(args, "y"));
            case "kill_mob" -> TestOps.killMob(session, intArg(args, "objectId"), boolArg(args, "withDrops", true));
            case "list_mobs" -> TestOps.listMobs(session);
            case "grant_item" -> TestOps.grantItem(session, intArg(args, "itemId"), intArg(args, "quantity", 1));
            case "grant_mesos" -> TestOps.grantMesos(session, intArg(args, "amount"));
            case "set_level" -> TestOps.setLevel(session, intArg(args, "level"));
            case "set_quest_state" -> TestOps.setQuestState(session, intArg(args, "questId"), strArg(args, "state"));
            case "spawn_drop" -> TestOps.spawnDrop(session, intArg(args, "itemId"),
                    intArg(args, "quantity", 1), intArg(args, "x"), intArg(args, "y"));
            case "grant_cash" -> TestOps.grantCash(session, intArg(args, "amount"), intArg(args, "type", 1));
            case "heal" -> TestOps.heal(session);
            default -> throw new IllegalArgumentException("Unknown setup op '" + op + "'");
        };
    }

    private static int intArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("setup argument '" + key + "' is required");
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof String s ? s : null;
    }
}
