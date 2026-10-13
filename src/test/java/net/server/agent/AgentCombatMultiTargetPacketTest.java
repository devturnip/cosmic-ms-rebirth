package net.server.agent;

import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the multi-target attack builders emit the same per-target block as
 * {@code AbstractDealDamageHandler#parseDamage} expects, repeated once per monster, with the
 * target count packed into the high nibble of {@code numAttackedAndDamage}.
 */
class AgentCombatMultiTargetPacketTest {

    @Test
    void closeRangePacketEncodesAllTargetsWithOneDamageLineEach() {
        InPacket packet = AgentCombat.buildAttackPacket(0, List.of(11, 22, 33), 555);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x31, packet.readUnsignedByte(), "3 attacked, 1 damage line");
        assertEquals(0, packet.readInt(), "skill id");
        packet.skip(8); // fixed 8-byte block
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(0, packet.readUnsignedByte(), "direction");
        assertEquals(0, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(6, packet.readUnsignedByte(), "speed");
        packet.skip(4); // unknown
        for (int oid : List.of(11, 22, 33)) {
            assertEquals(oid, packet.readInt(), "target oid");
            packet.skip(14); // per-target unknown block
            assertEquals(555, packet.readInt(), "damage");
            packet.skip(4); // trailing per-target
        }
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void rangedPacketEncodesAllTargets() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(0, List.of(7, 8), 100);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x21, packet.readUnsignedByte(), "2 attacked, 1 damage line");
        assertEquals(0, packet.readInt(), "skill id");
        packet.skip(8); // fixed 8-byte block
        packet.skip(7); // display, direction, stance, unused, speed, unused(ranged), rangedirection
        packet.skip(7); // fixed ranged block
        for (int oid : List.of(7, 8)) {
            assertEquals(oid, packet.readInt(), "target oid");
            packet.skip(14);
            assertEquals(100, packet.readInt(), "damage");
            packet.skip(4);
        }
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void singleTargetListMatchesSingleTargetOverload() {
        byte[] single = AgentCombat.buildAttackPacket(12345, 99, 42).getBytes();
        byte[] list = AgentCombat.buildAttackPacket(12345, List.of(99), 42).getBytes();

        assertArrayEquals(single, list);
    }
}
