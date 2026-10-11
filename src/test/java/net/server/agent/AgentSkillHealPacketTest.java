package net.server.agent;

import client.Character;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code SPECIAL_MOVE} heal packet against {@code SpecialMoveHandler}'s read
 * order: an int, the skill id, a byte skill level, then (optionally) trailing bytes.
 */
class AgentSkillHealPacketTest {

    private static final int HEAL = 2301002;

    @Test
    void buildHealPacketEncodesTimestampSkillIdAndLevel() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getSkillLevel(HEAL)).thenReturn(7);

        InPacket packet = AgentSkill.buildHealPacket(chr, HEAL);

        assertEquals(0, packet.readInt(), "unknown prefix (handler p.readInt())");
        assertEquals(HEAL, packet.readInt(), "skill id");
        assertEquals(7, packet.readUnsignedByte(), "skill level");
        assertEquals(0, packet.available(), "no trailing position: heal is centred on the caster");
    }

    @Test
    void onlyKnownHealSkillsAreTreatedAsHeals() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getSkillLevel(HEAL)).thenReturn(1);
        when(chr.getSkillLevel(1234567)).thenReturn(1);

        assertTrue(AgentSkill.isHealSkill(chr, HEAL));
        assertFalse(AgentSkill.isHealSkill(chr, 1234567), "arbitrary learned skills must not be heal-cast");
        assertFalse(AgentSkill.isHealSkill(chr, 0));
    }

    @Test
    void canCastHealIsFalseForUnknownSkillWithoutTouchingWz() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getSkillLevel(anyInt())).thenReturn(0);

        assertFalse(AgentSkill.canCastHeal(chr, 999999));
    }

    @Test
    void resolveHealSkillReturnsTheLearnedAllowListSkill() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getSkillLevel(HEAL)).thenReturn(3);

        int original = config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL;
        try {
            config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = 0;
            assertEquals(HEAL, AgentSkill.resolveHealSkill(chr));
        } finally {
            config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = original;
        }
    }

    @Test
    void resolveHealSkillIgnoresAnUnsupportedConfiguredId() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getSkillLevel(HEAL)).thenReturn(3);

        int original = config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL;
        try {
            // 1234567 is not on the heal allow-list, so it must fall back to the learned heal.
            config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = 1234567;
            assertEquals(HEAL, AgentSkill.resolveHealSkill(chr));
        } finally {
            config.YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = original;
        }
    }
}
