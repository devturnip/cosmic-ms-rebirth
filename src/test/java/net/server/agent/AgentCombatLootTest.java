package net.server.agent;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Verifies the non-mutating loot-eligibility rule against {@code MapItem#canBePickedBy}.
 */
class AgentCombatLootTest {

    private static Character character(int id, int partyId) {
        Character chr = Mockito.mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getPartyId()).thenReturn(partyId);
        return chr;
    }

    @Test
    void freeAndOwnedDropsAreLootable() {
        Character chr = character(10, -1);

        MapItem freeDrop = Mockito.mock(MapItem.class);
        when(freeDrop.getOwnerId()).thenReturn(0);
        assertTrue(AgentCombat.canLoot(chr, freeDrop));

        MapItem ownDrop = Mockito.mock(MapItem.class);
        when(ownDrop.getOwnerId()).thenReturn(10);
        assertTrue(AgentCombat.canLoot(chr, ownDrop));
    }

    @Test
    void anotherPlayersDropIsNotLootable() {
        Character chr = character(10, -1);

        MapItem otherDrop = Mockito.mock(MapItem.class);
        when(otherDrop.getOwnerId()).thenReturn(99);
        when(otherDrop.isFFADrop()).thenReturn(false);
        when(otherDrop.getPartyOwnerId()).thenReturn(-1);
        when(chr.isPartyMember(99)).thenReturn(false);

        assertFalse(AgentCombat.canLoot(chr, otherDrop));
    }

    @Test
    void partyAndExpiredDropsAreLootable() {
        Character chr = character(10, 5);

        MapItem partyDrop = Mockito.mock(MapItem.class);
        when(partyDrop.getOwnerId()).thenReturn(99);
        when(partyDrop.isFFADrop()).thenReturn(false);
        when(partyDrop.getPartyOwnerId()).thenReturn(5);
        assertTrue(AgentCombat.canLoot(chr, partyDrop));

        MapItem expiredDrop = Mockito.mock(MapItem.class);
        when(expiredDrop.getOwnerId()).thenReturn(99);
        when(expiredDrop.isFFADrop()).thenReturn(true);
        assertTrue(AgentCombat.canLoot(chr, expiredDrop));
    }

    @Test
    void partyMembersDropIsLootableEvenBeforePartyOwnershipIsSet() {
        Character chr = character(10, 5);

        MapItem memberDrop = Mockito.mock(MapItem.class);
        when(memberDrop.getOwnerId()).thenReturn(99);
        when(memberDrop.isFFADrop()).thenReturn(false);
        when(memberDrop.getPartyOwnerId()).thenReturn(-1);
        when(chr.isPartyMember(99)).thenReturn(true);

        assertTrue(AgentCombat.canLoot(chr, memberDrop));
    }
}
