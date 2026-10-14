package net.server.agent;

import client.Character;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCombatPacketTest {

    @Test
    void buildUseItemPacketEncodesTimestampSlotAndItemId() {
        InPacket packet = AgentCombat.buildUseItemPacket((short) 3, 2000000);

        assertEquals(0, packet.readInt(), "timestamp");
        assertEquals(3, packet.readShort(), "slot");
        assertEquals(2000000, packet.readInt(), "itemId");
        assertEquals(0, packet.available(), "no trailing bytes");
    }

    @Test
    void buildCloseRangeAttackPacketEncodesSingleTargetSingleDamageLine() {
        Character chr = Mockito.mock(Character.class);
        InPacket packet = AgentCombat.buildCloseRangeAttackPacket(chr, 4242, 777);

        assertEquals(0, packet.readUnsignedByte(), "unknown prefix byte");
        assertEquals(0x11, packet.readUnsignedByte(), "1 attacked, 1 damage line");
        assertEquals(0, packet.readInt(), "skill id");
        packet.skip(8); // charge / unknown
        assertEquals(0, packet.readUnsignedByte(), "display");
        assertEquals(22, packet.readUnsignedByte(), "direction");
        assertEquals(0, packet.readUnsignedByte(), "stance");
        assertEquals(0, packet.readUnsignedByte(), "unused");
        assertEquals(6, packet.readUnsignedByte(), "speed");
        packet.skip(4); // unknown
        assertEquals(4242, packet.readInt(), "target oid");
        packet.skip(14); // per-target unknown block
        assertEquals(777, packet.readInt(), "damage");
        assertEquals(0, packet.readInt(), "trailing per-target");
        assertEquals(0, packet.available(), "packet should be fully consumed");
    }
}
