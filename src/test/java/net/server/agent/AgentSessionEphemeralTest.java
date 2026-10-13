package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionEphemeralTest {

    @Test
    void ephemeralAndOwnerAreExposed() {
        AgentSession session = new AgentSession(new AgentClient(-1, null, 0, 0), null,
                new ScriptedAgentBrain("Test"), new AgentMemory(1), true, 42);

        assertTrue(session.isEphemeral());
        assertEquals(42, session.getOwnerPlayerId());
    }

    @Test
    void provisionedRecordRoundTrips() {
        AgentSession session = new AgentSession(new AgentClient(-1, null, 0, 0), null,
                new ScriptedAgentBrain("Test"), new AgentMemory(1), true, 42);
        AgentProvisioner.ProvisionedAgent provisioned =
                new AgentProvisioner.ProvisionedAgent(7, 8, "[AI]P", 12, 0);

        session.setProvisioned(provisioned);

        assertEquals(provisioned, session.getProvisioned());
    }

    @Test
    void defaultConstructorIsAPersistentOperatorSpawn() {
        AgentSession session = new AgentSession(new AgentClient(-1, null, 0, 0), null,
                new ScriptedAgentBrain("Test"), new AgentMemory(1));

        assertFalse(session.isEphemeral());
        assertEquals(-1, session.getOwnerPlayerId());
    }

    @Test
    void managerOwnerHelpersIgnoreNegativeIdsWithoutDb() {
        AgentManager manager = AgentManager.getInstance();
        assertEquals(0, manager.countOwnedBy(-1));
        assertTrue(manager.despawnOwnedBy(-1).isEmpty());
    }
}
