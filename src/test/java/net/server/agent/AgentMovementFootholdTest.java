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
    void moveToSmartFallsBackWhenTheAgentIsNotOnAFoothold() {
        // A lone wall foothold offers nothing to walk along.
        Foothold wall = new Foothold(new Point(50, -100), new Point(50, 100), 1);
        AgentSession session = sessionOn(treeWith(wall), 0, 0);

        AgentMovement.moveToSmart(session, new Point(300, 500), AgentMovement.MAX_STEP);

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
