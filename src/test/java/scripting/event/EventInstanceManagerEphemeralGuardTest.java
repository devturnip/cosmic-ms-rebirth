package scripting.event;

import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import net.server.agent.AgentManager;
import net.server.agent.AgentSession;
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
 * Verifies that {@link EventInstanceManager#registerPlayer(Character, boolean)} refuses to
 * register ephemeral agent companions — the primary defense keeping them out of party quests
 * (docs/llm-agent-pq-audit.md, M9).
 */
class EventInstanceManagerEphemeralGuardTest {

    private final List<AgentSession> injected = new ArrayList<>();

    private EventInstanceManager newEim() {
        EventManager em = mock(EventManager.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(em.getWorldServer()).thenReturn(world);
        when(em.getChannelServer()).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        return new EventInstanceManager(em, "guard-test");
    }

    private void injectEphemeral(int characterId) throws Exception {
        AgentSession session = mock(AgentSession.class);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.isEphemeral()).thenReturn(true);
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, AgentSession> sessions = (Map<Integer, AgentSession>) field.get(AgentManager.getInstance());
        sessions.put(characterId, session);
        injected.add(session);
    }

    @AfterEach
    void cleanup() throws Exception {
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        ((Map<Integer, ?>) field.get(AgentManager.getInstance())).keySet()
                .removeAll(injected.stream().map(AgentSession::getCharacterId).toList());
        injected.clear();
    }

    private Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private boolean isRegistered(EventInstanceManager eim, int characterId) throws Exception {
        Field field = EventInstanceManager.class.getDeclaredField("chars");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Character> chars = (Map<Integer, Character>) field.get(eim);
        return chars.containsKey(characterId);
    }

    @Test
    void ephemeralCompanionIsNeverRegistered() throws Exception {
        injectEphemeral(43001);
        EventInstanceManager eim = newEim();

        eim.registerPlayer(character(43001), false);

        assertFalse(isRegistered(eim, 43001), "the companion must not join the event instance");
    }

    @Test
    void regularPlayerIsRegistered() throws Exception {
        EventInstanceManager eim = newEim();

        eim.registerPlayer(character(43002), false);

        assertTrue(isRegistered(eim, 43002), "a regular player registers normally");
    }
}
