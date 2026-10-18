package scripting.event;

import net.server.agent.AgentManager;
import net.server.agent.AgentSession;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link EventManager#filterEphemeralAgents} drops ephemeral agent companions
 * from a PQ eligibility list so they can neither satisfy party-size gates nor be registered
 * into the instance (docs/llm-agent-pq-audit.md, M9).
 */
class EventEphemeralAgentFilterTest {

    private final AgentManager manager = AgentManager.getInstance();
    private final List<AgentSession> injected = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        ((Map<Integer, ?>) field.get(manager)).keySet()
                .removeAll(injected.stream().map(AgentSession::getCharacterId).toList());
        injected.clear();
    }

    private void injectEphemeral(int characterId) throws Exception {
        AgentSession session = mock(AgentSession.class);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.isEphemeral()).thenReturn(true);
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, AgentSession> sessions = (Map<Integer, AgentSession>) field.get(manager);
        sessions.put(characterId, session);
        injected.add(session);
    }

    private PartyCharacter member(int characterId) {
        PartyCharacter mpc = mock(PartyCharacter.class);
        when(mpc.getId()).thenReturn(characterId);
        return mpc;
    }

    @Test
    void ephemeralCompanionIsFilteredFromEligibility() throws Exception {
        injectEphemeral(42001);

        List<PartyCharacter> filtered = EventManager.filterEphemeralAgents(
                new ArrayList<>(List.of(member(42001), member(42002))));

        assertEquals(1, filtered.size(), "the companion is dropped");
        assertEquals(42002, filtered.get(0).getId(), "the human member survives");
    }

    @Test
    void partyWithoutCompanionsIsPassedThroughUnchanged() {
        List<PartyCharacter> members = new ArrayList<>(List.of(member(42003), member(42004)));

        List<PartyCharacter> filtered = EventManager.filterEphemeralAgents(members);

        assertEquals(members.size(), filtered.size(), "no member is dropped");
        assertSame(members.get(0), filtered.get(0), "order and instances are preserved");
        assertTrue(filtered.containsAll(members), "every member survives the filter");
    }
}
