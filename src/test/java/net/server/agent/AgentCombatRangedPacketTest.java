package net.server.agent;

import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the byte layout of {@link AgentCombat#buildRangedAttackPacket(int, int, int)} against
 * the {@code ranged} branch of {@code AbstractDealDamageHandler#parseDamage}, including the extra
 * 4-byte block read for Hurricane-like skills and the charge int for charge-list skills.
 */
class AgentCombatRangedPacketTest {

    private static final int TARGET_OID = 4242;
    private static final int DAMAGE = 777;

    /**
     * Decodes the ranged layout in parse-order. {@code charge} adds a 4-byte int after the skill
     * id; {@code rangedExtra} adds the 4-byte block skipped after the fixed ranged header.
     */
    private static void assertRangedLayout(InPacket packet, int skillId, boolean charge, boolean rangedExtra) {
        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x11, packet.readUnsignedByte(), "1 attacked, 1 damage line");
        assertEquals(skillId, packet.readInt(), "skill id");
        if (charge) {
            assertEquals(0, packet.readInt(), "charge int");
        }
        packet.skip(8); // fixed 8-byte block
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(0, packet.readUnsignedByte(), "direction");
        assertEquals(4, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(4, packet.readUnsignedByte(), "speed");
        assertEquals(0, packet.readUnsignedByte(), "unused (ranged)");
        assertEquals(0, packet.readUnsignedByte(), "rangedirection");
        packet.skip(7); // fixed ranged block
        if (rangedExtra) {
            packet.skip(4); // Hurricane/Piercing/Rapid Fire extra
        }
        assertEquals(TARGET_OID, packet.readInt(), "target oid");
        packet.skip(14); // per-target unknown block
        assertEquals(DAMAGE, packet.readInt(), "damage");
        packet.skip(4); // trailing per-target
    }

    @Test
    void buildRangedAttackPacketWithSkillZeroHasExactLayout() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(0, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        assertEquals(54, raw.length, "total packet length");
        assertRangedLayout(packet, 0, false, false);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void buildRangedAttackPacketWithHurricaneIncludesExtraBlock() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(3121004, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        assertEquals(58, raw.length, "total packet length with extra block");
        assertRangedLayout(packet, 3121004, false, true);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void buildRangedAttackPacketWithChargeSkillIncludesChargeInt() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(2121001, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        // Big Bang is on the charge list, so parseDamage reads an extra int before the header.
        assertEquals(58, raw.length, "total packet length with charge int");
        assertRangedLayout(packet, 2121001, true, false);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }
}
