package net.server.agent;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A logged-out character has its {@code map}/{@code inventory} nulled by a delayed cleanup task
 * (memory-leak fix in {@code Character#dispose}). Perception on such a disposed character must
 * return a degraded-but-valid snapshot instead of NPEing (seen as
 * {@code AgentPerception.collectNearbyPlayers} NPE after a PQ timeout).
 */
class AgentPerceptionDisposedCharacterTest {

    @Test
    void observeWithNullMapReturnsEmptySnapshotInsteadOfThrowing() {
        Character chr = mock(Character.class);
        when(chr.getMap()).thenReturn(null);
        when(chr.getPosition()).thenReturn(null);
        when(chr.getName()).thenReturn("DisposedAgent");

        AgentObservation obs = AgentPerception.observe(chr);

        assertEquals("DisposedAgent", obs.agentName());
        assertEquals("unknown", obs.mapName());
        assertEquals("unknown", obs.streetName());
        assertTrue(obs.nearbyPlayers().isEmpty());
        assertTrue(obs.partyMembers().isEmpty());
        assertTrue(obs.nearbyMonsters().isEmpty());
        assertTrue(obs.nearbyItems().isEmpty());
        assertTrue(obs.nearbyNpcs().isEmpty());
    }
}
