package net.server.agent;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionDispatcherMuteTest {

    @Test
    void mutedSessionDropsSayWithoutDispatchingAPacket() {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.isMuted()).thenReturn(true);

        boolean handled = new AgentActionDispatcher().dispatch(session, new AgentAction.Say("hello"));

        assertFalse(handled, "a muted chat action is not handled");
        verify(session, never()).dispatchInbound(any(), any());
        verify(session, never()).allowAction();
    }

    @Test
    void mutedSessionDropsPartySayAndWhisper() {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.isMuted()).thenReturn(true);
        AgentActionDispatcher dispatcher = new AgentActionDispatcher();

        assertFalse(dispatcher.dispatch(session, new AgentAction.PartySay("hi party")));
        assertFalse(dispatcher.dispatch(session, new AgentAction.Whisper("Bob", "psst")));
        verify(session, never()).dispatchInbound(any(), any());
    }

    @Test
    void muteDoesNotBlockNonChatActions() {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.isMuted()).thenReturn(true);
        when(session.allowAction()).thenReturn(true);

        boolean handled = new AgentActionDispatcher().dispatch(session, new AgentAction.StopFollowing());

        assertTrue(handled, "non-chat actions still run while muted");
    }
}
