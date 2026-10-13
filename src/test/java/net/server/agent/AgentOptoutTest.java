package net.server.agent;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOptoutTest {

    @Test
    void optedOutMembershipToggles() {
        AgentManager manager = AgentManager.getInstance();
        try {
            manager.applyOptedOut(4242, true);
            assertTrue(manager.isOptedOut(4242));
            manager.applyOptedOut(4242, false);
            assertFalse(manager.isOptedOut(4242));
        } finally {
            manager.applyOptedOut(4242, false);
        }
    }

    @Test
    void greetSkipsOptedOutPlayers() {
        Character chr = Mockito.mock(Character.class);
        Character other = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getId()).thenReturn(1);
        when(map.getAllPlayers()).thenReturn(List.of(other));
        when(other.getId()).thenReturn(2);
        when(other.isGM()).thenReturn(false);
        when(other.isHidden()).thenReturn(false);
        when(other.getPosition()).thenReturn(new Point(10, 0));
        // Without the opt-out guard this player WOULD be selected and greeted.
        when(session.isGreetDue(2, AgentConfig.greetCooldownMs())).thenReturn(true);
        when(session.getBrain()).thenReturn(new ScriptedAgentBrain("Test"));

        AgentManager.getInstance().applyOptedOut(2, true);
        try {
            assertFalse(AgentProactivity.greet(session), "an opted-out player must not be greeted");
            verify(session, never()).perform(any());
        } finally {
            AgentManager.getInstance().applyOptedOut(2, false);
        }
    }

    @Test
    void whisperToOptedOutPlayerIsDropped() {
        Character chr = Mockito.mock(Character.class);
        Character target = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getMap()).thenReturn(map);
        when(chr.getId()).thenReturn(1);
        when(map.getAllPlayers()).thenReturn(List.of(target));
        when(target.getName()).thenReturn("Bob");
        when(target.getId()).thenReturn(2);
        when(session.allowAction()).thenReturn(true);

        AgentManager.getInstance().applyOptedOut(2, true);
        try {
            new AgentActionDispatcher().dispatch(session, new AgentAction.Whisper("Bob", "hi"));
            verify(session, never()).dispatchInbound(any(), any());
        } finally {
            AgentManager.getInstance().applyOptedOut(2, false);
        }
    }
}
