package net.server.agent;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the player opt-out routing gates on {@link AgentManager}: chat/whisper/party-invite
 * events originating from (or inviting from) a player who opted out via {@code @agentoptout}
 * are suppressed, while the same events from a normal player are routed to the agent's inbox.
 */
class AgentManagerOptoutGateTest {

    private final AgentManager manager = AgentManager.getInstance();
    private final List<AgentSession> injected = new ArrayList<>();
    private final Set<Integer> optedOutIds = new HashSet<>();

    @AfterEach
    void cleanup() throws Exception {
        sessions().keySet().removeAll(injected.stream().map(AgentSession::getCharacterId).toList());
        injected.clear();
        for (int id : optedOutIds) {
            manager.applyOptedOut(id, false);
        }
        optedOutIds.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, AgentSession> sessions() throws Exception {
        Field field = AgentManager.class.getDeclaredField("sessionsByCharacterId");
        field.setAccessible(true);
        return (Map<Integer, AgentSession>) field.get(manager);
    }

    private void optOut(int characterId) {
        manager.applyOptedOut(characterId, true);
        optedOutIds.add(characterId);
    }

    private AgentSession injectAgent(int characterId, int playerId, MapleMap map) throws Exception {
        AgentSession session = mock(AgentSession.class);
        Character player = mock(Character.class);
        when(session.getCharacterId()).thenReturn(characterId);
        when(session.getPlayer()).thenReturn(player);
        when(player.getId()).thenReturn(playerId);
        when(player.getMap()).thenReturn(map);
        sessions().put(characterId, session);
        injected.add(session);
        return session;
    }

    // ------------------------------------------------------------------
    // onMapChat
    // ------------------------------------------------------------------

    @Test
    void mapChatFromAnOptedOutPlayerReachesNoAgent() throws Exception {
        MapleMap mapA = mock(MapleMap.class);
        MapleMap mapB = mock(MapleMap.class);
        AgentSession agentOnMapA = injectAgent(100, 1, mapA);
        AgentSession agentOnMapB = injectAgent(200, 2, mapB);
        optOut(300);

        manager.onMapChat(mapA, 300, "Bob", "hello agents", false);

        verify(agentOnMapA, never()).enqueue(org.mockito.ArgumentMatchers.any());
        verify(agentOnMapB, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapChatFromANormalPlayerReachesAgentsOnTheSameMapOnly() throws Exception {
        MapleMap mapA = mock(MapleMap.class);
        MapleMap mapB = mock(MapleMap.class);
        AgentSession agentOnMapA = injectAgent(100, 1, mapA);
        AgentSession agentOnMapB = injectAgent(200, 2, mapB);

        manager.onMapChat(mapA, 300, "Bob", "hello agents", false);

        verify(agentOnMapA).enqueue(new AgentEvent.MapChat(300, "Bob", "hello agents", false));
        verify(agentOnMapB, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapChatNeverReachesTheSpeakerHimself() throws Exception {
        MapleMap mapA = mock(MapleMap.class);
        // An agent whose own player is the speaker (same map).
        AgentSession speakingAgent = injectAgent(300, 300, mapA);
        AgentSession otherAgent = injectAgent(100, 1, mapA);

        manager.onMapChat(mapA, 300, "Agent-T", "hi", false);

        verify(speakingAgent, never()).enqueue(org.mockito.ArgumentMatchers.any());
        verify(otherAgent).enqueue(new AgentEvent.MapChat(300, "Agent-T", "hi", false));
    }

    // ------------------------------------------------------------------
    // onWhisper
    // ------------------------------------------------------------------

    @Test
    void whisperFromAnOptedOutPlayerIsNotDeliveredToTheAgent() throws Exception {
        AgentSession target = injectAgent(100, 1, mock(MapleMap.class));
        optOut(200);

        manager.onWhisper(100, 200, "Bob", "psst");

        verify(target, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void whisperFromANormalPlayerIsDeliveredToTheAgent() throws Exception {
        AgentSession target = injectAgent(100, 1, mock(MapleMap.class));

        manager.onWhisper(100, 200, "Bob", "psst");

        verify(target).enqueue(new AgentEvent.Whisper(200, "Bob", "psst"));
    }

    // ------------------------------------------------------------------
    // onPartyInvite
    // ------------------------------------------------------------------

    @Test
    void partyInviteFromAnOptedOutPlayerIsNotDeliveredToTheAgent() throws Exception {
        AgentSession invited = injectAgent(100, 1, mock(MapleMap.class));
        optOut(200);

        manager.onPartyInvite(100, 200, "Bob", 55);

        verify(invited, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void partyInviteFromANormalPlayerIsDeliveredToTheAgent() throws Exception {
        AgentSession invited = injectAgent(100, 1, mock(MapleMap.class));

        manager.onPartyInvite(100, 200, "Bob", 55);

        verify(invited).enqueue(new AgentEvent.PartyInvite(200, "Bob", 55));
    }

    // ------------------------------------------------------------------
    // control: the gates key on the *speaker/inviter*, not the agent's own opt-out
    // ------------------------------------------------------------------

    @Test
    void anOptedOutSpeakerIsSuppressedEvenIfTheAgentItselfIsNotOptedOut() throws Exception {
        MapleMap map = mock(MapleMap.class);
        AgentSession agent = injectAgent(100, 1, map);
        // Sanity: the agent's own character id is not on the opt-out list; only the speaker's is.
        optOut(200);
        if (manager.isOptedOut(100)) {
            manager.applyOptedOut(100, false);
        }

        manager.onWhisper(100, 200, "Bob", "psst");
        manager.onPartyInvite(100, 200, "Bob", 55);
        manager.onMapChat(map, 200, "Bob", "hi", false);

        verify(agent, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }
}
