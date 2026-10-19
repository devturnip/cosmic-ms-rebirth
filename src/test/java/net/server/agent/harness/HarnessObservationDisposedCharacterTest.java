package net.server.agent.harness;

import client.Character;
import net.server.agent.AgentClient;
import net.server.agent.AgentSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness {@code observe} on a dying session must yield a structured error payload, never an
 * exception (an NPE after a PQ timeout used to 500 the MCP call).
 */
class HarnessObservationDisposedCharacterTest {

    @Test
    void observeOnDisposedCharacterReturnsStructuredError() {
        AgentSession session = mock(AgentSession.class);
        Character chr = mock(Character.class);
        when(session.getPlayer()).thenReturn(chr);
        when(session.getClient()).thenReturn(mock(AgentClient.class));
        when(chr.getMap()).thenReturn(null); // disposed: delayed cleanup nulled the map

        Map<String, Object> out = HarnessObservation.describe(session, List.of());

        assertEquals("character session disposed; observation unavailable", out.get("error"));
        assertFalse(out.containsKey("monsters"), "no observation sections may be built for a disposed character");
    }

    @Test
    void observeOnUnloadedCharacterReturnsStructuredError() {
        AgentSession session = mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(null);
        when(session.getClient()).thenReturn(mock(AgentClient.class));

        Map<String, Object> out = HarnessObservation.describe(session, List.of());

        assertEquals("session character not loaded", out.get("error"));
    }
}
