package net.server.agent;

import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMovementTest {

    private static final int HEADER_BYTES = 9;

    @Test
    void buildMovePacketEncodesSingleAbsoluteCommand() {
        InPacket packet = AgentMovement.buildMovePacket(123, 456, 1);

        for (int i = 0; i < HEADER_BYTES; i++) {
            assertEquals(0, packet.readUnsignedByte(), "header byte " + i + " should be zero");
        }

        assertEquals(1, packet.readUnsignedByte(), "numCommands");
        assertEquals(0, packet.readUnsignedByte(), "command type");
        assertEquals(123, packet.readShort(), "x");
        assertEquals(456, packet.readShort(), "y");
        assertEquals(0, packet.readShort(), "xwobble");
        assertEquals(0, packet.readShort(), "ywobble");
        assertEquals(0, packet.readShort(), "foothold");
        assertEquals(1, packet.readUnsignedByte(), "stance");
        assertTrue(packet.readShort() > 0, "duration should be positive");
    }

    @Test
    void buildMovePacketRoundTripsSignedCoordinates() {
        InPacket packet = AgentMovement.buildMovePacket(-123, 456, 0);

        packet.skip(HEADER_BYTES);
        assertEquals(1, packet.readUnsignedByte(), "numCommands");
        assertEquals(0, packet.readUnsignedByte(), "command type");
        assertEquals(-123, packet.readShort(), "negative x should round-trip");
        assertEquals(456, packet.readShort(), "positive y should round-trip");
    }
}
