package net.server.agent;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ephemeral companions must never loot: their inventory is destroyed with them, so a drop would be
 * lost. The guard lives in the dispatcher so it also covers brain-driven {@code PickupItem} actions
 * (the scripted brain emits them for any nearby drop).
 */
class AgentActionDispatcherEphemeralLootTest {

    @Test
    void ephemeralCompanionDropsPickupItem() {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.isEphemeral()).thenReturn(true);
        when(session.allowAction()).thenReturn(true);

        boolean handled = new AgentActionDispatcher().dispatch(session, new AgentAction.PickupItem(1234));

        assertTrue(handled, "the action is accepted but intentionally a no-op for companions");
        verify(session, never()).dispatchInbound(any(), any());
    }

    @Test
    void persistentAgentStillAttemptsPickup() {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.isEphemeral()).thenReturn(false);
        when(session.allowAction()).thenReturn(true);
        // No player/map: pickupItemFor returns early, but the guard must not be what stopped it.
        when(session.getPlayer()).thenReturn(null);

        boolean handled = new AgentActionDispatcher().dispatch(session, new AgentAction.PickupItem(1234));

        assertTrue(handled);
    }
}
