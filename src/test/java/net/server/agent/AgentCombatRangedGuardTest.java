package net.server.agent;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pure guard tests for {@link AgentCombat}'s ranged-attack preconditions. These only exercise the
 * null-inventory short-circuits so no WZ data or database access is required.
 */
class AgentCombatRangedGuardTest {

    @Test
    void isRangedWeaponIsFalseWhenEquippedInventoryIsNull() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(null);

        assertFalse(AgentCombat.isRangedWeapon(chr), "no equipped inventory means no ranged weapon");
    }

    @Test
    void hasMatchingAmmoIsFalseWhenUseInventoryIsNull() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(null);

        assertFalse(AgentCombat.hasMatchingAmmo(chr, WeaponType.BOW, 1), "no USE inventory means no ammo");
    }

    @Test
    void canUseRangedAttackIsFalseWhenInventoriesAreNull() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getBuffedValue(any())).thenReturn(null);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(null);
        when(chr.getInventory(InventoryType.USE)).thenReturn(null);

        assertFalse(AgentCombat.canUseRangedAttack(chr), "no weapon or ammo means no ranged attack");
    }
}
