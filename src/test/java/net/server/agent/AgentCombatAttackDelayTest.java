package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.StatEffect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AgentCombat#attackDelayMs}: a real WZ cooldown always wins, a hold skill
 * (Hurricane family) without one gets the fixed after-delay, and every other skill is unthrottled.
 */
class AgentCombatAttackDelayTest {

    private static final int HURRICANE = 3121004;
    private static final int NORMAL_SKILL = 2001005;

    @Test
    void attackDelayUsesWzCooldownWhenPresent() {
        Skill skill = mock(Skill.class);
        Character chr = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 10);
        when(skill.getEffect(10)).thenReturn(effect);
        when(effect.getCooldown()).thenReturn(5);

        try (MockedStatic<SkillFactory> factory = Mockito.mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(HURRICANE)).thenReturn(skill);

            assertEquals(5000L, AgentCombat.attackDelayMs(chr, HURRICANE), "WZ cooldown wins");
        }
    }

    @Test
    void attackDelayUsesHoldIntervalForHoldSkillWithoutCooldown() {
        Skill skill = mock(Skill.class);
        Character chr = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 10);
        when(skill.getEffect(10)).thenReturn(effect);
        when(effect.getCooldown()).thenReturn(0);

        try (MockedStatic<SkillFactory> factory = Mockito.mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(HURRICANE)).thenReturn(skill);

            assertEquals(200L, AgentCombat.attackDelayMs(chr, HURRICANE), "hold after-delay");
        }
    }

    @Test
    void attackDelayIsZeroForNormalSkillWithoutCooldown() {
        Skill skill = mock(Skill.class);
        Character chr = mock(Character.class);
        StatEffect effect = mock(StatEffect.class);
        when(chr.getSkillLevel(skill)).thenReturn((byte) 10);
        when(skill.getEffect(10)).thenReturn(effect);
        when(effect.getCooldown()).thenReturn(0);

        try (MockedStatic<SkillFactory> factory = Mockito.mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(NORMAL_SKILL)).thenReturn(skill);

            assertEquals(0L, AgentCombat.attackDelayMs(chr, NORMAL_SKILL), "normal skill unthrottled");
        }
    }
}
