package net.server.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AgentManager#isEphemeralAgent(int)} — the seam the event-instance guard
 * (PQ compatibility, docs/llm-agent-pq-audit.md) and the PQ eligibility filter use to keep
 * ephemeral companions out of contexts that assume a durable participant.
 */
class AgentManagerEphemeralGuardTest {

    private final AgentManager manager = AgentManager.getInstance();
    private final List<AgentSession> injected = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        sessions().keySet().removeAll(injected.stream().map(AgentSession::getCharacterId).toList());
        injected.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, AgentSession> sessions() throws Exception {
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        return (Map<Integer, AgentSession>) field.get(manager);
    }

    private void inject(int characterId, boolean ephemeral) throws Exception {
        AgentSession session = mock(AgentSession.class);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.isEphemeral()).thenReturn(ephemeral);
        sessions().put(characterId, session);
        injected.add(session);
    }

    @Test
    void ephemeralSessionIsReported() throws Exception {
        inject(41001, true);
        assertTrue(manager.isEphemeralAgent(41001), "an ephemeral companion is reported");
    }

    @Test
    void persistentAgentIsNotReported() throws Exception {
        inject(41002, false);
        assertFalse(manager.isEphemeralAgent(41002), "a persistent GM agent is not ephemeral");
    }

    @Test
    void unknownCharacterIsNotReported() {
        assertFalse(manager.isEphemeralAgent(-987654321), "an unknown character id is not ephemeral");
    }
}
