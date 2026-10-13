package net.server.agent;

import client.Character;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that agent movement is clamped per axis (so a hallucinated destination cannot
 * teleport) and that smart stepping aligns horizontally before crossing platforms.
 */
class AgentMovementSmartTest {

    private static final int HEADER_BYTES = 9;

    private static AgentSession sessionAt(int x, int y) {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getPosition()).thenReturn(new Point(x, y));
        when(chr.getMap()).thenReturn(map);
        when(map.getMapArea()).thenReturn(null); // fall back to 16-bit clamping only
        return session;
    }

    private static Point captureMove(AgentSession session) {
        ArgumentCaptor<InPacket> captor = ArgumentCaptor.forClass(InPacket.class);
        verify(session).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), captor.capture());
        InPacket packet = captor.getValue();
        packet.skip(HEADER_BYTES);
        packet.skip(1); // numCommands
        packet.skip(1); // command type
        return new Point(packet.readShort(), packet.readShort());
    }

    @Test
    void moveToClampsBothAxesToMaxStep() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.moveTo(session, 1000, -1000);

        assertEquals(new Point(AgentMovement.MAX_STEP, -AgentMovement.MAX_STEP), captureMove(session));
    }

    @Test
    void moveToSmartAlignsHorizontallyWhenTargetIsOnAnotherPlatform() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.moveToSmart(session, new Point(300, 500), AgentMovement.MAX_STEP);

        assertEquals(new Point(AgentMovement.MAX_STEP, 0), captureMove(session), "should not drop until x is aligned");
    }

    @Test
    void moveToSmartStepsDiagonallyOnTheSamePlatform() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.moveToSmart(session, new Point(300, 10), AgentMovement.MAX_STEP);

        assertEquals(new Point(AgentMovement.MAX_STEP, 10), captureMove(session));
    }

    @Test
    void moveToWritesANonZeroVelocitySoClientsInterpolate() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.moveTo(session, 150, 0, 150);

        ArgumentCaptor<InPacket> captor = ArgumentCaptor.forClass(InPacket.class);
        verify(session).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), captor.capture());
        InPacket packet = captor.getValue();
        packet.skip(HEADER_BYTES);
        packet.skip(1); // numCommands
        packet.skip(1); // command type
        packet.skip(4); // x, y
        int vx = packet.readShort();
        assertNotEquals(0, vx, "a walking step must carry a non-zero x velocity");
    }

    @Test
    void verticalStepUsesTheClimbStance() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.moveTo(session, 0, 200, 200);

        ArgumentCaptor<InPacket> captor = ArgumentCaptor.forClass(InPacket.class);
        verify(session).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), captor.capture());
        InPacket packet = captor.getValue();
        packet.skip(HEADER_BYTES);
        packet.skip(1); // numCommands
        packet.skip(1); // command type
        packet.skip(4); // x, y
        packet.skip(4); // vx, vy
        packet.skip(2); // foothold
        assertEquals(16, packet.readByte(), "a vertical step should use the rope/ladder climb stance");
    }

    @Test
    void broadcastIdleResetsAWalkStance() {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getMap()).thenReturn(map);
        when(chr.getStance()).thenReturn(2); // walk right
        when(chr.isFacingLeft()).thenReturn(false);

        AgentMovement.broadcastIdle(session);

        verify(chr).broadcastStance(4); // stand, facing right
    }

    @Test
    void broadcastIdleDoesNothingWhenAlreadyStanding() {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getMap()).thenReturn(map);
        when(chr.getStance()).thenReturn(4); // already stand

        AgentMovement.broadcastIdle(session);

        verify(chr, Mockito.never()).broadcastStance(Mockito.anyInt());
    }

    @Test
    void stepAwayMovesDirectlyAwayFromTheThreat() {
        AgentSession session = sessionAt(0, 0);

        AgentMovement.stepAway(session, new Point(0, 0), new Point(100, 0), AgentMovement.MAX_STEP);

        assertEquals(new Point(-AgentMovement.MAX_STEP, 0), captureMove(session));
    }

    @Test
    void portalLookupMatchesTheTargetMap() {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        Portal portal = Mockito.mock(Portal.class);
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getTargetMapId()).thenReturn(999);
        when(portal.getPortalStatus()).thenReturn(true);
        when(chr.getMap()).thenReturn(map);
        when(map.getPortals()).thenReturn(List.of(portal));

        assertTrue(AgentMovement.hasPortalTo(chr, 999), "an open direct portal should be found");
        assertFalse(AgentMovement.hasPortalTo(chr, 1000), "a portal to another map should not match");
    }

    @Test
    void closedPortalIsNotUsed() {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        Portal portal = Mockito.mock(Portal.class);
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getTargetMapId()).thenReturn(999);
        when(portal.getPortalStatus()).thenReturn(false);
        when(chr.getMap()).thenReturn(map);
        when(map.getPortals()).thenReturn(List.of(portal));

        assertFalse(AgentMovement.hasPortalTo(chr, 999), "a closed portal must not be used");
    }

    private static AgentSession sessionWith(Character chr) {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        return session;
    }

    @Test
    void walkThroughPortalEntersItAndClearsTheTransitionFlag() {
        Character chr = Mockito.mock(Character.class);
        client.Client client = Mockito.mock(client.Client.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        Portal portal = Mockito.mock(Portal.class);
        when(chr.isAlive()).thenReturn(true);
        when(chr.isChangingMaps()).thenReturn(false);
        when(chr.getClient()).thenReturn(client);
        when(chr.getEventInstance()).thenReturn(null);
        when(chr.getTrade()).thenReturn(null);
        when(chr.getCashShop()).thenReturn(null);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getMapId()).thenReturn(999);
        when(map.getPortals()).thenReturn(List.of(portal));
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getTargetMapId()).thenReturn(999);
        when(portal.getPortalStatus()).thenReturn(true);
        when(portal.getScriptName()).thenReturn(null);
        when(portal.getPosition()).thenReturn(new Point(0, 0));

        boolean arrived = AgentMovement.walkThroughPortalTo(sessionWith(chr), 999);

        assertTrue(arrived, "entering should report arrival");
        verify(portal).enterPortal(client);
        verify(chr).setMapTransitionComplete();
    }

    @Test
    void walkThroughPortalWalksWhenStillFarAway() {
        Character chr = Mockito.mock(Character.class);
        client.Client client = Mockito.mock(client.Client.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        Portal portal = Mockito.mock(Portal.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        when(chr.isChangingMaps()).thenReturn(false);
        when(chr.getClient()).thenReturn(client);
        when(chr.getEventInstance()).thenReturn(null);
        when(chr.getTrade()).thenReturn(null);
        when(chr.getCashShop()).thenReturn(null);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(1000, 0));
        when(map.getPortals()).thenReturn(List.of(portal));
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getTargetMapId()).thenReturn(999);
        when(portal.getPortalStatus()).thenReturn(true);
        when(portal.getScriptName()).thenReturn(null);
        when(portal.getPosition()).thenReturn(new Point(0, 0));

        assertFalse(AgentMovement.walkThroughPortalTo(session, 999), "should still be walking");
        verify(portal, Mockito.never()).enterPortal(client);
        verify(session).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), Mockito.any());
    }
}
