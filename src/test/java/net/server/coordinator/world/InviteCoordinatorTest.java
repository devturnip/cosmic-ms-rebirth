package net.server.coordinator.world;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the stale-invite fix: a new invite for the same invitee + type must
 * replace a pending one instead of being silently dropped (putIfAbsent semantics used to
 * block re-invites until the ~3min timeout).
 */
class InviteCoordinatorTest {

    private static Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.isLoggedinWorld()).thenReturn(true);
        return chr;
    }

    @Test
    void createInviteRegistersPendingInvite() {
        Character inviter = character(1);
        int invitee = 2;

        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, inviter, 10, invitee));
        assertTrue(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));
    }

    @Test
    void newInviteReplacesStaleInviteForSameInviteeAndType() {
        Character staleInviter = character(1);
        Character realInviter = character(3);
        int invitee = 2;

        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, staleInviter, 10, invitee));
        assertTrue(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));

        // The real invite must no longer be a silent no-op.
        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, realInviter, 20, invitee));
        assertTrue(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));

        // The superseded sender's reference no longer matches: NOT_FOUND, invite kept.
        InviteCoordinator.InviteResult staleAnswer =
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 10, true);
        assertEquals(InviteCoordinator.InviteResultType.NOT_FOUND, staleAnswer.result);
        assertTrue(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));

        // The new sender's reference accepts and consumes the single pending invite.
        InviteCoordinator.InviteResult result =
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 20, true);
        assertEquals(InviteCoordinator.InviteResultType.ACCEPTED, result.result);
        assertSame(realInviter, result.from);
        assertFalse(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));
    }

    @Test
    void replacedInviteKeepsSinglePendingInvitePerTypeInvariant() {
        Character first = character(1);
        Character second = character(3);
        Character third = character(5);
        int invitee = 2;

        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, first, 10, invitee));
        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, second, 20, invitee));
        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, third, 30, invitee));

        // Exactly one pending invite: the last one created wins, earlier ones are gone.
        assertEquals(InviteCoordinator.InviteResultType.NOT_FOUND,
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 10, true).result);
        assertEquals(InviteCoordinator.InviteResultType.NOT_FOUND,
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 20, true).result);
        assertTrue(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));

        InviteCoordinator.InviteResult result =
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 30, true);
        assertEquals(InviteCoordinator.InviteResultType.ACCEPTED, result.result);
        assertFalse(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));
    }

    @Test
    void denialConsumesInvite() {
        Character inviter = character(1);
        int invitee = 2;

        assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY, inviter, 10, invitee));
        InviteCoordinator.InviteResult result =
                InviteCoordinator.answerInvite(InviteCoordinator.InviteType.PARTY, invitee, 10, false);
        assertEquals(InviteCoordinator.InviteResultType.DENIED, result.result);
        assertFalse(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, invitee));
    }
}
