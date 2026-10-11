package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigCombatDefaultsTest {

    @Test
    void potionPercentagesAreWithinValidRange() {
        int hp = AgentConfig.hpPotionPercent();
        int mp = AgentConfig.mpPotionPercent();
        assertTrue(hp > 0 && hp <= 100, "hp potion percent should be in (0,100]");
        assertTrue(mp > 0 && mp <= 100, "mp potion percent should be in (0,100]");
    }

    @Test
    void attackRangeSqIsPositive() {
        assertTrue(AgentConfig.attackRangeSq() > 0);
    }

    @Test
    void autoSustainAndAutoCombatAreCallableBooleans() {
        assertDoesNotThrow(() -> {
            boolean sustain = AgentConfig.autoSustain();
            boolean combat = AgentConfig.autoCombat();
            // Boolean accessors simply must be callable; no config-specific value is asserted.
            assertTrue(sustain || !sustain);
            assertTrue(combat || !combat);
        });
    }
}
