package net.server.agent;

import config.YamlConfig;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Verifies {@link AgentManager#transferChannel}: a failed re-spawn rolls back to the original
 * channel, muted/paused state survives the transfer, and transfers are refused for mid-transition
 * or null players (and throttled). No real threads are started: {@code spawn} is stubbed and the
 * ticker/brain pool are never created (the manager is a real-methods mock, not {@link #start() ed}).
 */
class AgentManagerTransferChannelTest {

    private AgentManager manager;
    private Map<Integer, AgentSession> sessions;
    private Map<Integer, Long> lastChannelTransferAt;
    private FakeProvisioner provisioner;
    private Boolean originalMemoryPersist;

    @BeforeEach
    void setUpManagerWithoutRealDependencies() throws Exception {
        originalMemoryPersist = YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST;
        YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST = false; // removeSession must not touch the DB

        manager = mock(AgentManager.class, withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        sessions = new ConcurrentHashMap<>();
        lastChannelTransferAt = new ConcurrentHashMap<>();
        setField("sessionsByCharacterId", sessions);
        setField("lastChannelTransferAt", lastChannelTransferAt);
        setField("pendingEphemeralDeletes", ConcurrentHashMap.newKeySet());
        provisioner = new FakeProvisioner();
        manager.setProvisioner(provisioner);
    }

    @AfterEach
    void tearDown() {
        if (originalMemoryPersist != null) {
            YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST = originalMemoryPersist;
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = AgentManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private AgentSession injectOldSession(int characterId, String name, int channel, boolean ephemeral,
                                          AgentProvisioner.ProvisionedAgent provisioned) {
        AgentSession session = mock(AgentSession.class);
        AgentClient client = mock(AgentClient.class);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.getName()).thenReturn(name);
        when(session.getClient()).thenReturn(client);
        when(client.getChannel()).thenReturn(channel);
        when(client.getWorld()).thenReturn(0);
        Character player = mock(Character.class);
        when(session.getPlayer()).thenReturn(player);
        when(player.isChangingMaps()).thenReturn(false);
        when(session.getFollowTargetId()).thenReturn(-1);
        when(session.isEphemeral()).thenReturn(ephemeral);
        when(session.getOwnerPlayerId()).thenReturn(7);
        when(session.getProvisioned()).thenReturn(provisioned);
        sessions.put(characterId, session);
        return session;
    }

    private static final AgentProvisioner.ProvisionedAgent PROVISIONED =
            new AgentProvisioner.ProvisionedAgent(9001, 55, "Agent-T", 30, 2, 0);

    // ------------------------------------------------------------------
    // (a) failed re-spawn rolls back to the original channel
    // ------------------------------------------------------------------

    @Test
    void failedTransferRollsBackToTheOriginalChannel() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        AgentSession rolledBack = mock(AgentSession.class);
        // The re-spawn on the target channel fails; the rollback on the old channel succeeds.
        stubSpawnByChannel(rolledBack);

        AgentSession result = manager.transferChannel(100, 3);

        assertSame(rolledBack, result, "a failed transfer must roll back to the original channel");
        verify(manager).spawn("Agent-T", 0, 3, null, null, true, 7, PROVISIONED);
        verify(manager).spawn("Agent-T", 0, 2, null, null, true, 7, PROVISIONED);
        verify(old).shutdown();
        verify(old.getClient()).forceDisconnect();
        assertFalse(provisioner.destroyed.contains(PROVISIONED),
                "the rollback succeeded, so the ephemeral character must survive");
    }

    @Test
    void aTransferWhoseRollbackAlsoFailsDestroysTheEphemeralCharacter() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        stubSpawnAlwaysFailing();

        assertNull(manager.transferChannel(100, 3));

        verify(old).shutdown();
        assertEquals(List.of(PROVISIONED), provisioner.destroyed,
                "an agent that can neither transfer nor roll back must not be left half-deleted");
        // The throttle is removed so a later transfer can retry after the failure.
        assertFalse(lastChannelTransferAt.containsKey(100));
    }

    // ------------------------------------------------------------------
    // (b) muted (and paused) state is captured and restored across the transfer
    // ------------------------------------------------------------------

    @Test
    void mutedAndPausedStateSurviveTheTransfer() {
        injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        when(sessions.get(100).isMuted()).thenReturn(true);
        when(sessions.get(100).isPaused()).thenReturn(true);
        AgentSession transferred = mock(AgentSession.class);
        stubSpawnByChannel(transferred);

        AgentSession result = manager.transferChannel(100, 3);

        assertSame(transferred, result);
        verify(transferred).setMuted(true);
        verify(transferred).setPaused(true);
    }

    @Test
    void unmutedUnpausedStateSurvivesTheTransfer() {
        injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        when(sessions.get(100).isMuted()).thenReturn(false);
        when(sessions.get(100).isPaused()).thenReturn(false);
        AgentSession transferred = mock(AgentSession.class);
        stubSpawnByChannel(transferred);

        manager.transferChannel(100, 3);

        verify(transferred).setMuted(false);
        verify(transferred).setPaused(false);
    }

    @Test
    void manualFollowTargetSurvivesTheTransfer() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        when(old.isMuted()).thenReturn(false);
        when(old.isPaused()).thenReturn(false);
        when(old.getFollowTargetId()).thenReturn(42);
        when(old.isManualFollow()).thenReturn(true);
        AgentSession transferred = mock(AgentSession.class);
        stubSpawnByChannel(transferred);

        manager.transferChannel(100, 3);

        verify(transferred).setManualFollow(42);
    }

    // ------------------------------------------------------------------
    // (c) transfers are refused for mid-transition / null players and throttled
    // ------------------------------------------------------------------

    @Test
    void transferIsRefusedForAMidTransitionPlayer() {
        Character midTransition = midTransitionCharacter();
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        when(old.getPlayer()).thenReturn(midTransition);

        assertNull(manager.transferChannel(100, 3), "never yank a character mid-transition");

        verify(manager, never()).spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
        verify(old, never()).shutdown();
        assertSame(old, sessions.get(100), "the session must remain registered");
    }

    @Test
    void transferIsRefusedForANullPlayer() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        when(old.getPlayer()).thenReturn(null);

        assertNull(manager.transferChannel(100, 3));

        verify(manager, never()).spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
        verify(old, never()).shutdown();
        assertSame(old, sessions.get(100));
    }

    @Test
    void transferIsRefusedForAnUnknownCharacterOrInvalidChannel() {
        assertNull(manager.transferChannel(999, 3));
        assertNull(manager.transferChannel(0, 3));
        assertNull(manager.transferChannel(100, 0));
        verify(manager, never()).spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
    }

    @Test
    void transferToTheSameChannelIsTheExistingSession() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);

        assertSame(old, manager.transferChannel(100, 2));

        verify(manager, never()).spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
        verify(old, never()).shutdown();
    }

    @Test
    void transfersAreThrottledPerCharacter() {
        AgentSession old = injectOldSession(100, "Agent-T", 2, true, PROVISIONED);
        lastChannelTransferAt.put(100, System.currentTimeMillis());

        assertNull(manager.transferChannel(100, 3), "a transfer within the cooldown must be throttled");

        verify(manager, never()).spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
        verify(old, never()).shutdown();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Character midTransitionCharacter() {
        Character chr = mock(Character.class);
        when(chr.isChangingMaps()).thenReturn(true);
        return chr;
    }

    /** Stubs the 8-arg spawn: fails on the target channel (3), succeeds on the old one (2). */
    private void stubSpawnByChannel(AgentSession success) {
        doAnswer(inv -> ((int) inv.getArgument(2)) == 3 ? null : success)
                .when(manager)
                .spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
    }

    private void stubSpawnAlwaysFailing() {
        doAnswer(inv -> null)
                .when(manager)
                .spawn(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyInt(), any());
    }

    /** Package-private fake provisioner recording destroyed companions. */
    static final class FakeProvisioner implements AgentProvisioner {
        final List<AgentProvisioner.ProvisionedAgent> destroyed = new ArrayList<>();

        @Override
        public ProvisionedAgent provision(int level, client.Job job, int mapId, int world) {
            return null;
        }

        @Override
        public void destroy(ProvisionedAgent agent) {
            if (agent != null) {
                destroyed.add(agent);
            }
        }
    }

}
