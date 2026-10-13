package net.server.agent;

import client.Character;
import client.Client;
import client.Job;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCompanionServiceTest {

    private static final int PLAYER_ID = 777;

    // saved config
    private boolean useAgents;
    private boolean playerSpawn;
    private int maxPerPlayer;
    private long cooldownMs;
    private int maxAgents;
    private String levelMatch;
    private boolean autoParty;
    private boolean complementJob;
    private String apiUrl;
    private String model;

    private FakeProvisioner provisioner;
    private FakeRuntime runtime;
    private CompanionPartyService partyService;
    private AtomicLong clock;
    private AgentCompanionService service;
    private Character player;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        var server = YamlConfig.config.server;
        useAgents = server.USE_LLM_AGENTS;
        playerSpawn = server.LLM_AGENT_PLAYER_SPAWN;
        maxPerPlayer = server.LLM_AGENT_MAX_PER_PLAYER;
        cooldownMs = server.LLM_AGENT_REQUEST_COOLDOWN_MS;
        maxAgents = server.LLM_AGENTS_MAX;
        levelMatch = server.LLM_AGENT_LEVEL_MATCH;
        autoParty = server.LLM_AGENT_AUTO_PARTY;
        complementJob = server.LLM_AGENT_COMPLEMENT_JOB;
        apiUrl = server.LLM_API_URL;
        model = server.LLM_MODEL;

        server.USE_LLM_AGENTS = true;
        server.LLM_AGENT_PLAYER_SPAWN = true;
        server.LLM_AGENT_MAX_PER_PLAYER = 1;
        server.LLM_AGENT_REQUEST_COOLDOWN_MS = 60_000L;
        server.LLM_AGENTS_MAX = 10;
        server.LLM_AGENT_LEVEL_MATCH = "EXACT";
        server.LLM_AGENT_AUTO_PARTY = true;
        server.LLM_AGENT_COMPLEMENT_JOB = true;
        server.LLM_API_URL = "";
        server.LLM_MODEL = "";

        AgentManager.getInstance().applyOptedOut(PLAYER_ID, false);

        provisioner = new FakeProvisioner();
        runtime = new FakeRuntime();
        partyService = mock(CompanionPartyService.class);
        clock = new AtomicLong(1_000L);
        service = new AgentCompanionService(provisioner, runtime, partyService, clock::get);

        session = mock(AgentSession.class);
        when(session.getCharacterId()).thenReturn(9001);
        when(session.getName()).thenReturn("[AI]Test");
        runtime.next = session;

        player = mockPlayer();
    }

    @AfterEach
    void tearDown() {
        var server = YamlConfig.config.server;
        server.USE_LLM_AGENTS = useAgents;
        server.LLM_AGENT_PLAYER_SPAWN = playerSpawn;
        server.LLM_AGENT_MAX_PER_PLAYER = maxPerPlayer;
        server.LLM_AGENT_REQUEST_COOLDOWN_MS = cooldownMs;
        server.LLM_AGENTS_MAX = maxAgents;
        server.LLM_AGENT_LEVEL_MATCH = levelMatch;
        server.LLM_AGENT_AUTO_PARTY = autoParty;
        server.LLM_AGENT_COMPLEMENT_JOB = complementJob;
        server.LLM_API_URL = apiUrl;
        server.LLM_MODEL = model;
        AgentManager.getInstance().applyOptedOut(PLAYER_ID, false);
    }

    private static Character mockPlayer() {
        Character character = mock(Character.class);
        Client client = mock(Client.class);
        MapleMap map = mock(MapleMap.class);
        when(character.isAlive()).thenReturn(true);
        when(character.getMap()).thenReturn(map);
        when(character.getEventInstance()).thenReturn(null);
        when(character.getTrade()).thenReturn(null);
        when(character.getCashShop()).thenReturn(null);
        when(character.getId()).thenReturn(PLAYER_ID);
        when(character.getClient()).thenReturn(client);
        when(character.getJob()).thenReturn(Job.WARRIOR);
        when(character.getLevel()).thenReturn(30);
        when(character.getMapId()).thenReturn(100_000_000);
        when(character.getWorld()).thenReturn(0);
        when(map.isEventMap()).thenReturn(false);
        when(client.getChannel()).thenReturn(1);
        return character;
    }

    @Test
    void disabledFeatureRefusesFind() {
        YamlConfig.config.server.LLM_AGENT_PLAYER_SPAWN = false;

        AgentCompanionService.Result result = service.find(player, null);

        assertEquals(AgentCompanionService.Status.DISABLED, result.status());
        assertEquals(0, provisioner.provisionCalls);
        assertEquals(0, runtime.spawnCalls);
    }

    @Test
    void perPlayerCapRefusesFind() {
        runtime.registerOwned(PLAYER_ID, session);

        AgentCompanionService.Result result = service.find(player, null);

        assertEquals(AgentCompanionService.Status.CAPPED, result.status());
        assertEquals(0, provisioner.provisionCalls);
    }

    @Test
    void cooldownRefusesSecondRequest() {
        YamlConfig.config.server.LLM_AGENT_MAX_PER_PLAYER = 2;

        assertTrue(service.find(player, null).ok());

        clock.set(1_000L + 59_999L);
        AgentCompanionService.Result throttled = service.find(player, null);
        assertEquals(AgentCompanionService.Status.COOLDOWN, throttled.status());

        clock.set(1_000L + 60_000L);
        assertTrue(service.find(player, null).ok());
    }

    @Test
    void successfulFindSpawnsFollowsAndParties() {
        AgentCompanionService.Result result = service.find(player, null);

        assertTrue(result.ok());
        assertEquals(1, provisioner.provisionCalls);
        assertEquals(Job.CLERIC, provisioner.lastJob, "a warrior should be complemented by a healer");
        assertEquals(1, runtime.spawnCalls);
        assertTrue(runtime.lastEphemeral);
        assertEquals(PLAYER_ID, runtime.lastOwner);
        assertEquals(1, runtime.lastChannel);
        assertEquals(0, runtime.lastWorld);
        verify(session).setManualFollow(PLAYER_ID);
        verify(partyService).autoParty(player, session);
        assertEquals(provisioner.lastProvisioned, runtime.lastProvisioned,
                "the provisioning record must reach the session so despawn can delete it");
    }

    @Test
    void explicitRoleOverridesComplement() {
        AgentCompanionService.Result result = service.find(player, "bowman");

        assertTrue(result.ok());
        assertEquals(Job.BOWMAN, provisioner.lastJob);
    }

    @Test
    void unknownRoleIsRejectedBeforeProvisioning() {
        AgentCompanionService.Result result = service.find(player, "wizardry");

        assertEquals(AgentCompanionService.Status.UNKNOWN_ROLE, result.status());
        assertEquals(0, provisioner.provisionCalls);
    }

    @Test
    void provisionFailureDoesNotSpawn() {
        provisioner.next = null;

        AgentCompanionService.Result result = service.find(player, null);

        assertEquals(AgentCompanionService.Status.PROVISION_FAILED, result.status());
        assertEquals(0, runtime.spawnCalls);
        assertTrue(provisioner.destroyed.isEmpty());
    }

    @Test
    void spawnFailureDestroysProvisionedCharacter() {
        runtime.next = null;

        AgentCompanionService.Result result = service.find(player, null);

        assertEquals(AgentCompanionService.Status.SPAWN_FAILED, result.status());
        assertEquals(1, provisioner.destroyed.size());
        assertEquals(provisioner.lastProvisioned, provisioner.destroyed.get(0));
    }

    @Test
    void dismissDespawnsOwnedCompanions() {
        assertTrue(service.find(player, null).ok());

        AgentCompanionService.Result result = service.dismiss(player);

        assertEquals(AgentCompanionService.Status.DISMISSED, result.status());
        assertEquals(0, runtime.countOwnedBy(PLAYER_ID));
        // Deletion is owned by AgentManager#despawn (so GM despawn / shutdown also clean up).
        assertTrue(provisioner.destroyed.isEmpty());
    }

    @Test
    void concurrentRequestsCannotExceedThePerPlayerCap() throws Exception {
        YamlConfig.config.server.LLM_AGENT_MAX_PER_PLAYER = 1;
        YamlConfig.config.server.LLM_AGENT_REQUEST_COOLDOWN_MS = 0L;

        CountDownLatch enteredProvision = new CountDownLatch(1);
        CountDownLatch releaseProvision = new CountDownLatch(1);
        provisioner.onProvision = () -> {
            enteredProvision.countDown();
            try {
                releaseProvision.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        AtomicReference<AgentCompanionService.Result> firstResult = new AtomicReference<>();
        AtomicReference<AgentCompanionService.Result> secondResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(service.find(player, null)));
        first.start();
        assertTrue(enteredProvision.await(2, TimeUnit.SECONDS), "first request should reach provisioning");

        Thread second = new Thread(() -> secondResult.set(service.find(player, null)));
        second.start();
        Thread.sleep(100); // let the second request block on the per-player lock
        releaseProvision.countDown();
        first.join(2_000);
        second.join(2_000);

        assertEquals(1, provisioner.provisionCalls, "only one companion may be provisioned under the cap");
        assertTrue(firstResult.get() != null && firstResult.get().ok());
        assertNotNull(secondResult.get());
        assertEquals(AgentCompanionService.Status.CAPPED, secondResult.get().status());
    }

    @Test
    void dismissWithoutCompanionIsANoOp() {
        AgentCompanionService.Result result = service.dismiss(player);

        assertEquals(AgentCompanionService.Status.NOTHING_TO_DISMISS, result.status());
        assertTrue(provisioner.destroyed.isEmpty());
        verify(partyService, never()).autoParty(player, session);
    }

    @Test
    void pauseWithoutCompanionIsAClearNoOp() {
        AgentCompanionService.Result result = service.pause(player, true);

        assertEquals(AgentCompanionService.Status.NOTHING_TO_PAUSE, result.status());
        assertTrue(result.message().toLowerCase().contains("no active companion"));
    }

    @Test
    void pauseAndResumeToggleTheCompanion() {
        assertTrue(service.find(player, null).ok());

        AgentCompanionService.Result paused = service.pause(player, true);
        assertEquals(AgentCompanionService.Status.PAUSED, paused.status());
        assertEquals(1, runtime.pausedOwnedBy(PLAYER_ID));

        AgentCompanionService.Result resumed = service.pause(player, false);
        assertEquals(AgentCompanionService.Status.RESUMED, resumed.status());
        assertEquals(0, runtime.pausedOwnedBy(PLAYER_ID));
    }

    @Test
    void pauseIsDisabledWithTheFeature() {
        YamlConfig.config.server.LLM_AGENT_PLAYER_SPAWN = false;
        runtime.registerOwned(PLAYER_ID, session); // even a live companion cannot be toggled

        AgentCompanionService.Result result = service.pause(player, true);

        assertEquals(AgentCompanionService.Status.DISABLED, result.status());
        assertEquals(0, runtime.pausedOwnedBy(PLAYER_ID));
    }

    @Test
    void describeAvailableReportsThePausedState() {
        assertTrue(service.find(player, null).ok());
        service.pause(player, true);

        assertTrue(service.describeAvailable(player).stream().anyMatch(line -> line.contains("paused")));
    }

    // ------------------------------------------------------------------
    // fakes
    // ------------------------------------------------------------------

    private static final class FakeProvisioner implements AgentProvisioner {
        AgentProvisioner.ProvisionedAgent next =
                new AgentProvisioner.ProvisionedAgent(4242, 24, "[AI]Nova", 30, Job.CLERIC.getId());
        AgentProvisioner.ProvisionedAgent lastProvisioned;
        final List<AgentProvisioner.ProvisionedAgent> destroyed = new ArrayList<>();
        Runnable onProvision;
        int provisionCalls;
        Job lastJob;
        int lastLevel;
        int lastMapId;
        int lastWorld;

        @Override
        public AgentProvisioner.ProvisionedAgent provision(int level, Job job, int mapId, int world) {
            provisionCalls++;
            lastJob = job;
            lastLevel = level;
            lastMapId = mapId;
            lastWorld = world;
            lastProvisioned = next;
            if (onProvision != null) {
                onProvision.run();
            }
            return next;
        }

        @Override
        public void destroy(AgentProvisioner.ProvisionedAgent agent) {
            if (agent != null && agent.isValid()) {
                destroyed.add(agent);
            }
        }

        @Override
        public List<String> roles() {
            return AgentRole.names();
        }
    }

    private static final class FakeRuntime implements AgentCompanionService.CompanionRuntime {
        final Map<Integer, List<AgentSession>> owned = new LinkedHashMap<>();
        final Set<Integer> pausedOwners = new HashSet<>();
        int active;
        AgentSession next;
        int spawnCalls;
        String lastName;
        int lastWorld;
        int lastChannel;
        boolean lastEphemeral;
        int lastOwner;
        AgentProvisioner.ProvisionedAgent lastProvisioned;

        void registerOwned(int owner, AgentSession session) {
            owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(session);
            active++;
        }

        @Override
        public AgentSession spawn(String name, int world, int channel, AgentBrain brain,
                                  boolean ephemeral, int ownerPlayerId,
                                  AgentProvisioner.ProvisionedAgent provisioned) {
            spawnCalls++;
            lastName = name;
            lastWorld = world;
            lastChannel = channel;
            lastEphemeral = ephemeral;
            lastOwner = ownerPlayerId;
            lastProvisioned = provisioned;
            if (next != null) {
                registerOwned(ownerPlayerId, next);
            }
            return next;
        }

        @Override
        public int countOwnedBy(int ownerPlayerId) {
            return owned.getOrDefault(ownerPlayerId, List.of()).size();
        }

        @Override
        public int activeCount() {
            return active;
        }

        @Override
        public List<AgentSession> despawnOwnedBy(int ownerPlayerId) {
            List<AgentSession> removed = owned.remove(ownerPlayerId);
            if (removed == null) {
                return List.of();
            }
            active -= removed.size();
            pausedOwners.remove(ownerPlayerId);
            return removed;
        }

        @Override
        public int setPausedOwnedBy(int ownerPlayerId, boolean paused) {
            if (ownerPlayerId < 0) {
                return 0;
            }
            if (paused) {
                pausedOwners.add(ownerPlayerId);
            } else {
                pausedOwners.remove(ownerPlayerId);
            }
            return countOwnedBy(ownerPlayerId);
        }

        @Override
        public int pausedOwnedBy(int ownerPlayerId) {
            return pausedOwners.contains(ownerPlayerId) ? countOwnedBy(ownerPlayerId) : 0;
        }
    }
}
