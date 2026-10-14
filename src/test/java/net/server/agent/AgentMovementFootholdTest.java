package net.server.agent;

import client.Character;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the additive foothold-aware stepping: when the agent's map exposes footholds and the
 * target sits on a different foothold, the agent walks along its current foothold (clamped to that
 * foothold's x-range) before dropping toward the landing foothold. When no footholds are available
 * the movement must fall back to exactly the old per-axis behaviour.
 */
class AgentMovementFootholdTest {

    private static final int HEADER_BYTES = 9;

    private static AgentSession sessionOn(FootholdTree tree, int x, int y) {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getPosition()).thenReturn(new Point(x, y));
        when(chr.getMap()).thenReturn(map);
        when(map.getMapArea()).thenReturn(null); // fall back to 16-bit clamping only
        when(map.getFootholds()).thenReturn(tree);
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

    private static FootholdTree treeWith(Foothold... footholds) {
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000), 0);
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        return tree;
    }

    private static Foothold upper() {
        return new Foothold(new Point(0, 0), new Point(200, 0), 1);
    }

    private static Foothold lower() {
        return new Foothold(new Point(100, 200), new Point(400, 200), 2);
    }

    @Test
    void moveToSmartWalksAlongCurrentFootholdTowardTargetXBeforeDropping() {
        AgentSession session = sessionOn(treeWith(upper(), lower()), 10, 0);

        AgentMovement.moveToSmart(session, new Point(300, 200), AgentMovement.MAX_STEP);

        // Walks to the current foothold's right edge (200) but only by MAX_STEP; no drop yet.
        assertEquals(new Point(160, 0), captureMove(session));
    }

    @Test
    void moveToSmartDropsTowardLandingFootholdOnceAligned() {
        AgentSession session = sessionOn(treeWith(upper(), lower()), 200, 0);

        AgentMovement.moveToSmart(session, new Point(300, 200), AgentMovement.MAX_STEP);

        // Already at the edge: drop toward the landing foothold, clamped to MAX_STEP vertically.
        assertEquals(new Point(300, AgentMovement.MAX_STEP), captureMove(session));
    }

    @Test
    void stepTowardUsesFootholdAwareWalking() {
        AgentSession session = sessionOn(treeWith(upper(), lower()), 10, 0);

        AgentMovement.stepToward(session, new Point(10, 0), new Point(300, 200), AgentMovement.MAX_STEP);

        assertEquals(new Point(160, 0), captureMove(session));
    }

    @Test
    void moveToSmartFallsBackToPlainStepWhenTargetSharesTheCurrentFoothold() {
        Foothold wide = new Foothold(new Point(0, 0), new Point(500, 0), 1);
        AgentSession session = sessionOn(treeWith(wide), 10, 0);

        AgentMovement.moveToSmart(session, new Point(300, 0), AgentMovement.MAX_STEP);

        // Same foothold: plain per-axis clamped step (10 + 150), not a special walk/drop.
        assertEquals(new Point(10 + AgentMovement.MAX_STEP, 0), captureMove(session));
    }

    @Test
    void moveToSmartFallsBackWhenTheMapHasNoFootholds() {
        AgentSession session = sessionOn(null, 0, 0);

        AgentMovement.moveToSmart(session, new Point(300, 500), AgentMovement.MAX_STEP);

        // Old behaviour: align horizontally first, no vertical movement while doing so.
        assertEquals(new Point(AgentMovement.MAX_STEP, 0), captureMove(session));
    }

    @Test
    void footholdLookupRecoversWhenTheAgentSitsSlightlyBelowTheSurface() {
        AgentSession session = sessionOn(treeWith(upper(), lower()), 100, 5);

        AgentMovement.moveToSmart(session, new Point(300, 200), AgentMovement.MAX_STEP);

        // findBelow skips the current foothold (agent is below it), so enumeration must recover it.
        assertEquals(new Point(200, 0), captureMove(session));
    }

    @Test
    void moveToSmartNeverAscendsTowardAHigherPlatform() {
        // Agent standing on the lower platform, target on the higher one above it.
        AgentSession session = sessionOn(treeWith(upper(), lower()), 100, 200);

        AgentMovement.moveToSmart(session, new Point(150, 0), AgentMovement.MAX_STEP);

        // It may walk horizontally, but it must stay on its own platform (y = 200), never fly up.
        assertEquals(new Point(150, 200), captureMove(session));
    }

    @Test
    void moveToSmartStaysPutWhenTheTargetIsDirectlyAbove() {
        AgentSession session = sessionOn(treeWith(upper(), lower()), 100, 200);

        AgentMovement.moveToSmart(session, new Point(100, 0), AgentMovement.MAX_STEP);

        // No horizontal gap and no way to climb: do not move at all rather than walk into the air.
        verify(session, Mockito.never()).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), Mockito.any());
    }

    @Test
    void airborneAgentFallsStraightDownToTheFootholdBelow() {
        Foothold ground = new Foothold(new Point(0, 200), new Point(400, 200), 1);
        AgentSession session = sessionOn(treeWith(ground), 100, 0);

        AgentMovement.moveToSmart(session, new Point(400, 200), AgentMovement.MAX_STEP);

        // Falls toward the surface under its own x (150 = 0 + MAX_STEP), with no horizontal drift.
        assertEquals(new Point(100, AgentMovement.MAX_STEP), captureMove(session));
    }

    @Test
    void walkingAgentStopsAtThePlatformEdgeOverAGap() {
        Foothold platform = new Foothold(new Point(0, 0), new Point(200, 0), 1);
        AgentSession session = sessionOn(treeWith(platform), 100, 0);

        AgentMovement.moveToSmart(session, new Point(400, 0), AgentMovement.MAX_STEP);

        // The target is over a pit: stop at the edge (200) instead of stepping into thin air.
        assertEquals(new Point(200, 0), captureMove(session));
    }

    @Test
    void walkingAgentAtTheEdgeDoesNotStepIntoAGap() {
        Foothold platform = new Foothold(new Point(0, 0), new Point(200, 0), 1);
        AgentSession session = sessionOn(treeWith(platform), 200, 0);

        AgentMovement.moveToSmart(session, new Point(400, 0), AgentMovement.MAX_STEP);

        verify(session, Mockito.never()).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), Mockito.any());
    }

    @Test
    void moveToSmartHoldsWhenAirborneWithNoFootholdBelow() {
        // A lone wall offers nothing to land on: the agent must hold rather than drift sideways.
        Foothold wall = new Foothold(new Point(50, -100), new Point(50, 100), 1);
        AgentSession session = sessionOn(treeWith(wall), 0, 0);

        AgentMovement.moveToSmart(session, new Point(300, 500), AgentMovement.MAX_STEP);

        verify(session, Mockito.never()).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), Mockito.any());
    }

    @Test
    void walkingAgentDoesNotCrossAGapSmallerThanTheStep() {
        // A [0,200] and B [300,600] at the same height, with a 100px void between them. A 250px step
        // would land on B (the old behaviour) - it must stop at A's edge instead.
        Foothold left = new Foothold(new Point(0, 0), new Point(200, 0), 1);
        Foothold right = new Foothold(new Point(300, 0), new Point(600, 0), 2);
        AgentSession session = sessionOn(treeWith(left, right), 150, 0);

        AgentMovement.moveToSmart(session, new Point(500, 0), 250);

        assertEquals(new Point(200, 0), captureMove(session), "must not step across the void");
    }

    @Test
    void walkOnGroundNeverStepsUpOntoAHigherPlatform() {
        // B is 70px ABOVE A but overlaps in x. A horizontal walk must not climb onto it.
        Foothold lower = new Foothold(new Point(0, 0), new Point(200, 0), 1);
        Foothold higher = new Foothold(new Point(150, -70), new Point(400, -70), 2);
        AgentSession session = sessionOn(treeWith(lower, higher), 100, 0);

        AgentMovement.moveToSmart(session, new Point(300, -70), AgentMovement.MAX_STEP);

        assertEquals(new Point(200, 0), captureMove(session), "must stay on its own platform");
    }

    @Test
    void footholdLookupDoesNotTreatAnOverheadPlatformAsGround() {
        // The agent is 40px BELOW an overhead platform with nothing else around. It must not treat
        // that platform as ground and climb sideways/up onto it (a real-map air-walk the WZ-backed
        // runtime test surfaced).
        Foothold overhead = new Foothold(new Point(0, 0), new Point(400, 0), 1);
        AgentSession session = sessionOn(treeWith(overhead), 100, 40);

        AgentMovement.moveToSmart(session, new Point(300, 0), AgentMovement.MAX_STEP);

        verify(session, Mockito.never()).dispatchInbound(eq(RecvOpcode.MOVE_PLAYER), Mockito.any());
    }

    @Test
    void getAllFootholdsCollectsEveryFootholdIncludingNestedQuadrants() {
        // A tiny root forces footholds into child quadrants (recursing to max depth).
        FootholdTree tree = new FootholdTree(new Point(0, 0), new Point(10, 10), 0);
        Foothold first = new Foothold(new Point(100, 100), new Point(200, 100), 1);
        Foothold second = new Foothold(new Point(300, 300), new Point(400, 300), 2);
        tree.insert(first);
        tree.insert(second);

        List<Foothold> all = tree.getAllFootholds();

        assertEquals(2, all.size(), "both nested footholds should be collected");
        assertTrue(all.contains(first), "first nested foothold should be present");
        assertTrue(all.contains(second), "second nested foothold should be present");
    }
}
