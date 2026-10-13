package net.server.agent;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapleMap;
import net.server.world.Party;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * Guard tests for proactive behavior: each entry point must no-op safely when its preconditions
 * are not met (no map, following, or not the party leader).
 */
class AgentProactivityGuardTest {

    @Test
    void greetReturnsFalseWithoutAMap() {
        Character chr = Mockito.mock(Character.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMap()).thenReturn(null);

        assertFalse(AgentProactivity.greet(session));
    }

    @Test
    void matchmakeSkipsWhenNotThePartyLeader() {
        Character chr = Mockito.mock(Character.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        Party party = Mockito.mock(Party.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        when(chr.getLevel()).thenReturn(30);
        when(chr.getId()).thenReturn(1);
        when(chr.getParty()).thenReturn(party);
        when(party.getMembers()).thenReturn(List.of());
        when(party.getLeaderId()).thenReturn(999);

        assertFalse(AgentProactivity.matchmake(session));
    }

    @Test
    void patrolSkipsWhileFollowing() {
        Character chr = Mockito.mock(Character.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(session.getFollowTargetId()).thenReturn(5);

        assertFalse(AgentProactivity.patrol(session));
    }
}
