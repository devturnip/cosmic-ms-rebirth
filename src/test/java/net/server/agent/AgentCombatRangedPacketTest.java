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
        assertRangedLayout(packet, skillId, charge, rangedExtra, 0, 0, 4);
    }

    /**
     * Decodes the ranged layout in parse-order with explicit direction/rangedirection/stance.
     * For hold skills stance mirrors rangedirection; other ranged skills keep stance 4.
     */
    private static void assertRangedLayout(InPacket packet, int skillId, boolean charge, boolean rangedExtra,
            int direction, int rangedirection, int stance) {
        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x11, packet.readUnsignedByte(), "1 attacked, 1 damage line");
        assertEquals(skillId, packet.readInt(), "skill id");
        if (charge) {
            assertEquals(0, packet.readInt(), "charge int");
        }
        packet.skip(8); // fixed 8-byte block
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(direction, packet.readUnsignedByte(), "direction");
        assertEquals(stance, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(4, packet.readUnsignedByte(), "speed");
        assertEquals(0, packet.readUnsignedByte(), "unused (ranged)");
        assertEquals(rangedirection, packet.readUnsignedByte(), "rangedirection");
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
        // A hold skill rebroadcasts stance = rangedirection, here both 0.
        assertRangedLayout(packet, 3121004, false, true, 0, 0, 0);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void buildRangedAttackPacketHoldSkillStanceMirrorsDirection() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(3121004, TARGET_OID, DAMAGE, 1, 1);

        // direction 1 (left) => stance and rangedirection both 1 for Hurricane.
        assertRangedLayout(packet, 3121004, false, true, 1, 1, 1);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void buildRangedAttackPacketNormalSkillKeepsStanceFour() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(0, TARGET_OID, DAMAGE, 1, 1);

        // direction/rangedirection 1, but a normal ranged attack keeps stance 4.
        assertRangedLayout(packet, 0, false, false, 1, 1, 4);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void buildRangedAttackPacketWithChargeSkillIncludesChargeInt() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(2121001, TARGET_OID, DAMAGE);
        byte[] raw = packet.getBytes();

        // Big Bang is on the charge list, so parseDamage reads an extra int before the header.
        assertEquals(58, raw.length, "total packet length with charge int");
        assertRangedLayout(packet, 2121001, true, false, 0, 0, 4);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }
}
