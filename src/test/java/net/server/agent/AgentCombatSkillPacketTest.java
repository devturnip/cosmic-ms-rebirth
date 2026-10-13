package net.server.agent;

import client.Character;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the byte layout of {@link AgentCombat#buildAttackPacket(int, int, int)} against the
 * fields read by {@code AbstractDealDamageHandler#parseDamage}, including the extra charge int
 * for charge-list skills and the Poison Bomb trailing position.
 */
class AgentCombatSkillPacketTest {

    private static final int TARGET_OID = 4242;
    private static final int DAMAGE = 777;

    /** Reads the layout shared by every attack packet up to and including the trailing skip. */
    private static void assertBaseLayout(InPacket packet, int skillId, boolean charge, byte[] raw, int expectedLength) {
        assertEquals(expectedLength, raw.length, "total packet length");

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x11, packet.readUnsignedByte(), "1 attacked, 1 damage line");
        assertEquals(skillId, packet.readInt(), "skill id");
        if (charge) {
            assertEquals(0, packet.readInt(), "charge int");
        }
        packet.skip(8); // fixed 8-byte block
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(0, packet.readUnsignedByte(), "direction");
        assertEquals(0, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(6, packet.readUnsignedByte(), "speed");
        packet.skip(4); // unknown
        assertEquals(TARGET_OID, packet.readInt(), "target oid");
        packet.skip(14); // per-target unknown block
        assertEquals(DAMAGE, packet.readInt(), "damage");
        packet.skip(4); // trailing per-target
    }

    @Test
    void buildAttackPacketWithoutSkillHasExactLayout() {
        InPacket packet = AgentCombat.buildAttackPacket(0, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        assertBaseLayout(packet, 0, false, raw, 49);
        assertEquals(0, packet.available(), "no trailing bytes");
    }

    @Test
    void buildAttackPacketWithNormalSkillHasExactLayout() {
        InPacket packet = AgentCombat.buildAttackPacket(2001002, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        assertBaseLayout(packet, 2001002, false, raw, 49);
        assertEquals(0, packet.available(), "no trailing bytes");
    }

    @Test
    void buildAttackPacketWithChargeSkillIncludesZeroChargeIntBeforeTarget() {
        InPacket packet = AgentCombat.buildAttackPacket(2121001, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        // Charge skills (e.g. Big Bang) add a 4-byte charge int after the skill id.
        assertBaseLayout(packet, 2121001, true, raw, 53);
        assertEquals(0, packet.available(), "no trailing bytes after charge int layout");
    }

    @Test
    void buildAttackPacketWithPoisonBombIncludesTrailingPosition() {
        InPacket packet = AgentCombat.buildAttackPacket(14111006, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        // Poison Bomb is both a charge skill (4) and appends skip(4) + two shorts (4).
        assertBaseLayout(packet, 14111006, true, raw, 61);

        // Trailing Poison Bomb position tail: 4-byte skip then two shorts.
        assertEquals(8, packet.available(), "trailing skip + position");
        packet.skip(4);
        assertEquals(0, packet.readShort(), "position x");
        assertEquals(0, packet.readShort(), "position y");
        assertEquals(0, packet.available(), "no trailing bytes after position");
    }

    @Test
    void buildCloseRangeAttackPacketMatchesZeroSkillAttackPacket() {
        Character chr = Mockito.mock(Character.class);

        byte[] closeRange = AgentCombat.buildCloseRangeAttackPacket(chr, TARGET_OID, DAMAGE).getBytes();
        byte[] zeroSkill = AgentCombat.buildAttackPacket(0, TARGET_OID, DAMAGE).getBytes();

        assertArrayEquals(zeroSkill, closeRange, "close-range packet should mirror the skill-0 packet");
    }
}
