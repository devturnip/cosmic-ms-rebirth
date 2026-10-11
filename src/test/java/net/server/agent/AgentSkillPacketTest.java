package net.server.agent;

import client.Character;
import net.packet.InPacket;
import net.server.PlayerBuffValueHolder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

class AgentSkillPacketTest {

    @Test
    void buildBuffPacketEncodesZeroTargetCloseRangeAttackHeader() {
        InPacket packet = AgentSkill.buildBuffPacket(1001003);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0, packet.readUnsignedByte(), "numAttackedAndDamage: 0 targets, 0 damage lines");
        assertEquals(1001003, packet.readInt(), "skill id");
        packet.skip(8); // charge / unknown
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(0, packet.readUnsignedByte(), "direction");
        assertEquals(4, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(4, packet.readUnsignedByte(), "speed");
        packet.skip(4); // unknown
        assertEquals(0, packet.available(), "exactly 23 bytes should be consumed");
    }

    @Test
    void isBuffActiveIsFalseWhenCharacterHasNoBuffs() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getAllBuffs()).thenReturn(Collections.emptyList());

        assertFalse(AgentSkill.isBuffActive(chr, 1001003));
    }

    @Test
    void isBuffActiveIsFalseForNonPositiveSkillId() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getAllBuffs()).thenReturn(Collections.<PlayerBuffValueHolder>emptyList());

        assertFalse(AgentSkill.isBuffActive(chr, 0));
    }

    @Test
    void canCastBuffIsFalseForNonPositiveSkillId() {
        Character chr = Mockito.mock(Character.class);

        // Guard clause short-circuits before any SkillFactory/WZ access.
        assertFalse(AgentSkill.canCastBuff(chr, 0));
    }
}
