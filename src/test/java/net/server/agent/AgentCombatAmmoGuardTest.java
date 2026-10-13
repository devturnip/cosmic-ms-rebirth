package net.server.agent;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Pure guard tests for the ammo helpers. These only exercise null-inventory/NPC short-circuits,
 * so no WZ data or database access is required.
 */
class AgentCombatAmmoGuardTest {

    @Test
    void ammoCountIsZeroWithoutRangedWeapon() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(null);

        assertEquals(0, AgentCombat.ammoCount(chr), "no ranged weapon means no usable ammo");
    }

    @Test
    void outOfAmmoIsFalseForNonRangedCharacter() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(null);

        assertFalse(AgentCombat.outOfAmmo(chr), "a melee agent is never out of ammo");
    }

    @Test
    void findRechargeShopIsNullWithoutPosition() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getPosition()).thenReturn(null);

        assertNull(AgentCombat.findRechargeShop(chr), "no position means no NPC in range");
    }

    @Test
    void findRechargeShopIsNullWhenMapHasNoNpcs() {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getMap()).thenReturn(map);
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList())).thenReturn(List.of());

        assertNull(AgentCombat.findRechargeShop(chr), "an empty map has nothing to recharge from");
    }

    @Test
    void findRechargeShopIsNullWithoutMap() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getMap()).thenReturn(null);

        assertNull(AgentCombat.findRechargeShop(chr));
    }

    @Test
    void rechargeAmmoRefusesWhenNoRangedWeaponEquipped() {
        Character chr = Mockito.mock(Character.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        when(chr.getClient()).thenReturn(Mockito.mock(client.Client.class));
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(null);

        assertFalse(AgentCombat.rechargeAmmo(session), "a melee agent has nothing to recharge");
    }
}
