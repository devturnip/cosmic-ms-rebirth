package net.server.agent;

import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the attack builders emit {@code damageLines} damage ints per target and pack the
 * line count into the low nibble of {@code numAttackedAndDamage}, matching
 * {@code AbstractDealDamageHandler#parseDamage}.
 */
class AgentCombatMultiLinePacketTest {

    @Test
    void closeRangePacketRepeatsDamageForEveryLine() {
        InPacket packet = AgentCombat.buildAttackPacket(0, List.of(11, 22), 333, 3);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x23, packet.readUnsignedByte(), "2 attacked, 3 damage lines");
        assertEquals(0, packet.readInt(), "skill id");
        packet.skip(8);
        packet.skip(5); // display, direction, stance, unused, speed
        packet.skip(4); // unknown
        for (int oid : List.of(11, 22)) {
            assertEquals(oid, packet.readInt(), "target oid");
            packet.skip(14);
            for (int line = 0; line < 3; line++) {
                assertEquals(333, packet.readInt(), "damage line " + line);
            }
            packet.skip(4);
        }
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void rangedPacketRepeatsDamageForEveryLine() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(0, List.of(5), 250, 2);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x12, packet.readUnsignedByte(), "1 attacked, 2 damage lines");
        assertEquals(0, packet.readInt(), "skill id");
        packet.skip(8);
        packet.skip(7); // display, direction, stance, unused, speed, unused, rangedirection
        packet.skip(7); // fixed ranged block
        assertEquals(5, packet.readInt(), "target oid");
        packet.skip(14);
        assertEquals(250, packet.readInt(), "first line");
        assertEquals(250, packet.readInt(), "second line");
        packet.skip(4);
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }

    @Test
    void lineCountIsClampedToEightLines() {
        InPacket zero = AgentCombat.buildAttackPacket(0, List.of(1), 9, 0);
        zero.skip(1);
        assertEquals(0x11, zero.readUnsignedByte(), "1 target, 0 lines clamps up to 1");

        InPacket tooMany = AgentCombat.buildAttackPacket(0, List.of(1), 9, 99);
        tooMany.skip(1);
        assertEquals(0x18, tooMany.readUnsignedByte(), "1 target, 99 lines clamps down to the 8-line cap");
    }

    @Test
    void targetCountIsCappedAtNibbleAndDuplicatesDropped() {
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(i);
        }
        many.add(0); // duplicate
        InPacket packet = AgentCombat.buildAttackPacket(0, many, 1, 1);

        packet.skip(1);
        assertEquals(0xF1, packet.readUnsignedByte(), "target count clamps to 15, 1 damage line");
    }

    @Test
    void basicAttackStillCarriesExactlyOneLine() {
        InPacket packet = AgentCombat.buildAttackPacket(0, List.of(7), 42);
        packet.skip(1);
        assertEquals(0x11, packet.readUnsignedByte(), "1 attacked, 1 damage line");
    }

    @Test
    void damageLinesForBasicSkillDoesNotNeedCharacter() {
        assertEquals(1, AgentCombat.damageLines(null, 0));
    }
}
