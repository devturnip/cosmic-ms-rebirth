package net.server.agent;

import client.Character;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Verifies that attacks carry the facing direction derived from the target's position, for both
 * the close-range and ranged packet layouts.
 */
class AgentCombatFacingTest {

    @Test
    void facingDirectionPointsAtTheTarget() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getPosition()).thenReturn(new Point(100, 0));

        assertEquals(1, AgentCombat.facingDirection(chr, new Point(0, 0)), "target to the left");
        assertEquals(0, AgentCombat.facingDirection(chr, new Point(200, 0)), "target to the right");
    }

    @Test
    void facingDirectionDefaultsToRightWhenPositionsAreMissing() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getPosition()).thenReturn(null);

        assertEquals(0, AgentCombat.facingDirection(chr, new Point(0, 0)));
        assertEquals(0, AgentCombat.facingDirection(chr, null));
    }

    @Test
    void closeRangePacketCarriesFacingDirection() {
        InPacket packet = AgentCombat.buildAttackPacket(0, 5, 100, 1, 1);

        assertEquals(0, packet.readUnsignedByte(), "prefix");
        assertEquals(0x11, packet.readUnsignedByte(), "1 target, 1 line");
        packet.skip(4); // skill id
        packet.skip(8); // fixed block
        packet.skip(1); // display
        assertEquals(23, packet.readUnsignedByte(), "direction should be the left attack action (22|1)");
        packet.skip(1); // stance
    }

    @Test
    void rangedPacketCarriesDirectionAndRangeDirection() {
        InPacket packet = AgentCombat.buildRangedAttackPacket(0, 5, 100, 1, 1);

        packet.skip(1); // prefix
        packet.skip(1); // numAttackedAndDamage
        packet.skip(4); // skill id
        packet.skip(8); // fixed block
        packet.skip(1); // display
        assertEquals(23, packet.readUnsignedByte(), "direction");
        packet.skip(1); // stance
        packet.skip(1); // unused
        packet.skip(1); // speed
        packet.skip(1); // unused
        assertEquals(1, packet.readUnsignedByte(), "rangedirection");
    }
}
