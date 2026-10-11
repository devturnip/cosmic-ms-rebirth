package net.server.agent;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AgentSkillHealTest {

    private static Character character(int id, int mapId, int hp, int maxHp, Point pos) {
        Character chr = Mockito.mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getHp()).thenReturn(hp);
        when(chr.getCurrentMaxHp()).thenReturn(maxHp);
        when(chr.getPosition()).thenReturn(pos);
        return chr;
    }

    private static void withHealPercent(int percent, Runnable body) {
        int original = YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT;
        try {
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = percent;
            body.run();
        } finally {
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = original;
        }
    }

    @Test
    void healthyAgentWithNoWoundedPartyNeedsNoHeal() {
        withHealPercent(90, () -> {
            Character self = character(1, 100, 100, 100, new Point(0, 0));
            when(self.getPartyMembersOnline()).thenReturn(List.of());

            assertFalse(AgentSkill.needsHeal(self));
        });
    }

    @Test
    void woundedAgentNeedsHeal() {
        withHealPercent(90, () -> {
            Character self = character(1, 100, 40, 100, new Point(0, 0));
            when(self.getPartyMembersOnline()).thenReturn(List.of());

            assertTrue(AgentSkill.needsHeal(self));
        });
    }

    @Test
    void nearbyWoundedPartyMemberTriggersHeal() {
        withHealPercent(90, () -> {
            Character self = character(1, 100, 100, 100, new Point(0, 0));
            Character ally = character(2, 100, 30, 100, new Point(50, 0));
            when(self.getPartyMembersOnline()).thenReturn(List.of(ally));

            assertTrue(AgentSkill.needsHeal(self));
        });
    }

    @Test
    void distantOrOtherMapPartyMembersAreIgnored() {
        withHealPercent(90, () -> {
            Character self = character(1, 100, 100, 100, new Point(0, 0));
            Character farAlly = character(2, 100, 10, 100, new Point(100_000, 0));
            Character otherMapAlly = character(3, 999, 10, 100, new Point(10, 0));
            when(self.getPartyMembersOnline()).thenReturn(List.of(farAlly, otherMapAlly));

            assertFalse(AgentSkill.needsHeal(self));
        });
    }
}
