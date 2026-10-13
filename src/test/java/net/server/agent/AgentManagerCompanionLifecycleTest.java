package net.server.agent;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link AgentManager} deletes ephemeral companion characters when their session
 * ends by any path (dismiss, GM despawn, shutdown), and never deletes a persistent agent.
 */
class AgentManagerCompanionLifecycleTest {

    private final AgentManager manager = AgentManager.getInstance();
    private final List<AgentSession> injected = new ArrayList<>();
    private AgentProvisioner originalProvisioner;
    private Boolean originalMemoryPersist;
    private Boolean originalUseAgents;

    @AfterEach
    void cleanup() throws Exception {
        sessions().keySet().removeAll(injected.stream().map(AgentSession::getCharacterId).toList());
        injected.clear();
        if (originalProvisioner != null) {
            manager.setProvisioner(originalProvisioner);
        }
        if (originalMemoryPersist != null) {
            YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST = originalMemoryPersist;
        }
        if (originalUseAgents != null) {
            YamlConfig.config.server.USE_LLM_AGENTS = originalUseAgents;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, AgentSession> sessions() throws Exception {
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        return (Map<Integer, AgentSession>) field.get(manager);
    }

    private void swapProvisioner(AgentProvisioner replacement) throws Exception {
        Field field = AgentManager.class.getDeclaredField("provisioner");
        field.setAccessible(true);
        originalProvisioner = (AgentProvisioner) field.get(manager);
        manager.setProvisioner(replacement);
    }

    private AgentSession inject(int characterId, String name, boolean ephemeral, int ownerPlayerId,
                                AgentProvisioner.ProvisionedAgent provisioned) throws Exception {
        AgentSession session = mock(AgentSession.class);
        AgentClient client = mock(AgentClient.class);
        AgentMemory memory = new AgentMemory(characterId);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.getName()).thenReturn(name);
        when(session.isEphemeral()).thenReturn(ephemeral);
        when(session.getOwnerPlayerId()).thenReturn(ownerPlayerId);
        when(session.getProvisioned()).thenReturn(provisioned);
        when(session.getMemory()).thenReturn(memory);
        when(session.getClient()).thenReturn(client);
        sessions().put(characterId, session);
        injected.add(session);
        return session;
    }

    @Test
    void despawnDeletesAnEphemeralCompanion() throws Exception {
        AgentProvisioner.ProvisionedAgent provisioned =
                new AgentProvisioner.ProvisionedAgent(9001, 55, "[AI]Test", 30, 2);
        inject(9001, "[AI]Test", true, 777, provisioned);
        FakeProvisioner fake = new FakeProvisioner();
        swapProvisioner(fake);

        assertEquals(1, manager.countOwnedBy(777));
        assertTrue(manager.despawn(9001));
        assertEquals(List.of(provisioned), fake.destroyed);
        assertEquals(0, manager.countOwnedBy(777));
    }

    @Test
    void despawnDoesNotDeleteAPersistentAgent() throws Exception {
        originalMemoryPersist = YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST;
        YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST = false;
        inject(9002, "[AI]Old", false, -1, null);
        FakeProvisioner fake = new FakeProvisioner();
        swapProvisioner(fake);

        assertTrue(manager.despawn(9002));
        assertTrue(fake.destroyed.isEmpty(), "persistent GM agents are never deleted by despawn");
    }

    @Test
    void spawnRefusesWhileStopping() throws Exception {
        originalUseAgents = YamlConfig.config.server.USE_LLM_AGENTS;
        YamlConfig.config.server.USE_LLM_AGENTS = true;
        Field stopping = AgentManager.class.getDeclaredField("stopping");
        stopping.setAccessible(true);
        boolean original = stopping.getBoolean(manager);
        stopping.setBoolean(manager, true);
        Field tickerField = AgentManager.class.getDeclaredField("ticker");
        tickerField.setAccessible(true);
        Object originalTicker = tickerField.get(manager);
        tickerField.set(manager, null);
        try {
            // Refused before any DB lookup, and must not call start() to recreate the ticker.
            assertNull(manager.spawn("[AI]Nobody", 0, 1, new ScriptedAgentBrain("test")));
            assertNull(tickerField.get(manager), "spawn must not restart the ticker while stopping");
        } finally {
            stopping.setBoolean(manager, original);
            tickerField.set(manager, originalTicker);
        }
    }

    private static final class FakeProvisioner implements AgentProvisioner {
        final List<ProvisionedAgent> destroyed = new ArrayList<>();

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
