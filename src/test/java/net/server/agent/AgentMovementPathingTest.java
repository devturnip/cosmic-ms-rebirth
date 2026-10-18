package net.server.agent;

import client.Character;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import provider.Data;
import provider.DataTool;
import provider.wz.XMLWZFile;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests for the foothold/rope pathfinder ({@link AgentPathing}) reachable through
 * {@link AgentMovement#moveToSmart}: a rope climb onto a strictly higher platform, a jump across
 * a small gap, graceful failure on unreachable targets (bounded search, no movement), and a
 * real-{@code Map.wz} rope route that skips when the WZ tree is unavailable (mirroring
 * {@code AgentMovementWzRuntimeTest}).
 *
 * <p>Every simulated tick asserts the §4.6/§4.19 invariants: per-axis clamping to
 * {@code MAX_STEP}, and positions that are always on a real foothold or on a real rope.
 */
class AgentMovementPathingTest {
    private static final int HEADER_BYTES = 9;
    private static final int MAX_STEP = AgentMovement.MAX_STEP;
    /** Fake map ids for the ladder cache (no real WZ map id collides, order never matters). */
    private static final int MAP_ROPE = 987001;
    private static final int MAP_JUMP = 987002;
    private static final int MAP_NONE = 987003;
    private static final int MAP_BIG = 987004;
    /** Real map used by the WZ-backed rope test: Maple Island (rope x=432, y −83..213). */
    private static final int WZ_MAP_ID = 1010000;
    private static final int WZ_ROPE_X = 432;

    private static XMLWZFile mapSource;

    @BeforeAll
    static void loadMapWz() {
        Path mapWz = locateMapWz();
        if (mapWz != null) {
            mapSource = new XMLWZFile(mapWz);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Unit: rope climb
    // -----------------------------------------------------------------------------------------

    @Test
    void ropeClimbReachesAHigherPlatform() {
        // Ground y=0 [0,200]; top y=-200 [100,300]; rope at x=150 spanning -200..0.
        FootholdTree tree = treeWith(
                new Foothold(new Point(0, 0), new Point(200, 0), 1),
                new Foothold(new Point(100, -200), new Point(300, -200), 2));
        AgentPathing.putLadders(MAP_ROPE, List.of(
                new AgentPathing.Ladder(150, -200, 0, false, true, 1)));
        int[] position = {50, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = sessionOn(tree, MAP_ROPE, position, stances);

        List<Point> path = drive(session, position, new Point(250, -200), 12);

        assertFalse(path.isEmpty(), "the agent never moved");
        assertTrue(position[1] == -200 && position[0] >= 100 && position[0] <= 300,
                "agent should stand on the higher platform, got " + position[0] + "," + position[1]);
        assertTrue(stances.contains(AgentMovement.CLIMB_STANCE),
                "the climb must use the rope/ladder stance 16, stances=" + stances);
        for (Point p : path) {
            assertTrue(onPlatform(p, 0, 0, 200) || onPlatform(p, -200, 100, 300) || onRope(p, 150, -200, 0),
                    "agent left the ground/rope: " + p + " path=" + path);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Unit: gap jump
    // -----------------------------------------------------------------------------------------

    @Test
    void jumpClearsASmallGap() {
        // Two same-height platforms with a 60px void between them.
        FootholdTree tree = treeWith(
                new Foothold(new Point(0, 0), new Point(200, 0), 1),
                new Foothold(new Point(260, 0), new Point(460, 0), 2));
        AgentPathing.putLadders(MAP_JUMP, List.of());
        int[] position = {100, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = sessionOn(tree, MAP_JUMP, position, stances);

        List<Point> path = drive(session, position, new Point(400, 0), 8);

        assertEquals(new Point(400, 0), new Point(position[0], position[1]),
                "agent should cross the gap and reach the target");
        assertTrue(stances.contains(AgentMovement.JUMP_STANCE_RIGHT),
                "crossing the gap must use the jump stance 6, stances=" + stances);
        for (Point p : path) {
            assertTrue(onPlatform(p, 0, 0, 200) || onPlatform(p, 0, 260, 460),
                    "agent left the platforms: " + p + " path=" + path);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Unit: unreachable targets fail gracefully (bounded, no movement)
    // -----------------------------------------------------------------------------------------

    @Test
    void unreachableTargetStallsGracefullyWithoutLooping() {
        FootholdTree tree = treeWith(new Foothold(new Point(0, 0), new Point(200, 0), 1));
        AgentPathing.putLadders(MAP_NONE, List.of());
        int[] position = {100, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = sessionOn(tree, MAP_NONE, position, stances);

        List<Point> path = drive(session, position, new Point(400, 0), 10);

        // One walk to the platform edge, then nothing: no air-walking, no infinite retry packets.
        assertEquals(1, path.size(), "only the edge walk should be emitted, path=" + path);
        assertEquals(new Point(200, 0), path.get(0));
        assertEquals(new Point(200, 0), new Point(position[0], position[1]));
    }

    @Test
    void pathingDeclinesOnOversizedMaps() {
        // 300 narrow platforms with gaps: above MAX_PATH_FOOTHOLDS, routing must decline safely.
        FootholdTree tree = new FootholdTree(new Point(-100, -100), new Point(5000, 1000), 0);
        for (int i = 0; i < 300; i++) {
            tree.insert(new Foothold(new Point(i * 30, 0), new Point(i * 30 + 10, 0), i + 1));
        }
        AgentPathing.putLadders(MAP_BIG, List.of());
        int[] position = {5, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = sessionOn(tree, MAP_BIG, position, stances);

        List<Point> path = drive(session, position, new Point(4500, 0), 10);

        assertEquals(1, path.size(), "only the edge walk should be emitted, path=" + path);
        assertEquals(new Point(10, 0), path.get(0));
    }

    // -----------------------------------------------------------------------------------------
    // Real WZ: rope route on Maple Island (skips without wz/Map.wz, like AgentMovementWzRuntimeTest)
    // -----------------------------------------------------------------------------------------

    @Test
    void realMapRopeRouteReachesAHigherPlatform() {
        assumeTrue(mapSource != null, "WZ Map.wz unavailable");
        FootholdTree tree = loadFootholds(WZ_MAP_ID);
        assumeTrue(tree != null, "Maple Island footholds unavailable");
        List<AgentPathing.Ladder> ladders = parseLadders(WZ_MAP_ID);
        assumeTrue(!ladders.isEmpty(), "Maple Island has no ladderRope data");
        AgentPathing.putLadders(WZ_MAP_ID, ladders);

        // Rope: x=432, y -83..213 (usable). Bottom platform y=215 covers 432; top platform
        // y=-85 covers 432. Target on the top platform, offset from the rope column.
        int[] position = {WZ_ROPE_X, 215};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = sessionOn(tree, WZ_MAP_ID, position, stances);

        List<Point> path = drive(session, position, new Point(490, -85), 15);

        assertTrue(position[1] == -85 && position[0] >= 360 && position[0] <= 540,
                "agent should reach the y=-85 platform via the rope, got "
                        + position[0] + "," + position[1] + " path=" + path);
        assertTrue(stances.contains(AgentMovement.CLIMB_STANCE),
                "the route must climb the rope, stances=" + stances);
        for (Point p : path) {
            assertTrue(footholdWithin(tree, p, 20) != null || onRope(p, WZ_ROPE_X, -83, 213),
                    "agent left ground and rope on a real map: " + p + " path=" + path);
        }
    }

    @Test
    void ladderRopeLoadsThroughTheWzProvider() {
        assumeTrue(mapSource != null, "WZ Map.wz unavailable");
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(map.getId()).thenReturn(104000000); // Lith Harbor: ladder at x=2151

        List<AgentPathing.Ladder> ladders = List.of();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ladders = AgentPathing.ladders(map);
            if (!ladders.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(ladders.isEmpty(), "the lazy Map.wz ladder load should warm asynchronously");
        assertTrue(ladders.stream().anyMatch(l -> l.usable() && l.x() == 2151),
                "expected Lith Harbor's usable ladder at x=2151, got " + ladders);
    }

    // -----------------------------------------------------------------------------------------
    // Simulation helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Drives up to {@code ticks} ground-aware steps, updating the position from each emitted
     * MOVE_PLAYER packet. Asserts the per-tick clamp invariant and records every emitted position
     * and stance byte.
     */
    private static List<Point> drive(AgentSession session, int[] position, Point target, int ticks) {
        List<Point> path = new ArrayList<>();
        Point[] emitted = emittedBox(session);
        for (int i = 0; i < ticks; i++) {
            int px = position[0];
            int py = position[1];
            emitted[0] = null;
            AgentMovement.moveToSmart(session, target, MAX_STEP);
            Point next = emitted[0];
            if (next != null) {
                assertTrue(Math.abs(next.x - px) <= MAX_STEP && Math.abs(next.y - py) <= MAX_STEP,
                        "step exceeded MAX_STEP: " + next + " from " + new Point(px, py));
                position[0] = next.x;
                position[1] = next.y;
                path.add(new Point(next.x, next.y));
            }
        }
        return path;
    }

    private static Point[] emittedBox(AgentSession session) {
        return ((SessionBox) sessionBoxMap.computeIfAbsent(session, s -> new SessionBox())).emitted;
    }

    private static final java.util.Map<AgentSession, SessionBox> sessionBoxMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class SessionBox {
        final Point[] emitted = new Point[1];
    }

    private static boolean onPlatform(Point p, int y, int minX, int maxX) {
        return Math.abs(p.y - y) <= 2 && p.x >= minX && p.x <= maxX;
    }

    private static boolean onRope(Point p, int ropeX, int top, int bottom) {
        return Math.abs(p.x - ropeX) <= 26 && p.y >= top - 26 && p.y <= bottom + 26;
    }

    /** The non-wall foothold supporting {@code (x,y)} within {@code tolerance}, or {@code null}. */
    private static Foothold footholdWithin(FootholdTree tree, Point p, int tolerance) {
        Foothold best = null;
        int bestGap = tolerance + 1;
        for (Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (p.x < minX || p.x > maxX) {
                continue;
            }
            int gap = Math.abs(fh.calculateFooting(p.x) - p.y);
            if (gap <= tolerance && gap < bestGap) {
                best = fh;
                bestGap = gap;
            }
        }
        return best;
    }

    private static FootholdTree treeWith(Foothold... footholds) {
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000), 0);
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        return tree;
    }

    /**
     * A mocked session backed by a mutable position; every synthesized MOVE_PLAYER packet's
     * absolute coordinates land in {@code position} via the driver and its stance byte in
     * {@code stances}.
     */
    private static AgentSession sessionOn(FootholdTree tree, int mapId, int[] position,
            List<Integer> stances) {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        // Track the climbing flag like the real session would (position classification reads it).
        java.util.concurrent.atomic.AtomicBoolean climbing = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(session.isClimbingRope()).thenAnswer(inv -> climbing.get());
        doAnswer(inv -> {
            climbing.set(inv.getArgument(0));
            return null;
        }).when(session).setClimbingRope(org.mockito.ArgumentMatchers.anyBoolean());
        when(chr.getPosition()).thenAnswer(invocation -> new Point(position[0], position[1]));
        when(chr.getMap()).thenReturn(map);
        when(map.getId()).thenReturn(mapId);
        when(map.getFootholds()).thenReturn(tree);
        when(map.getMapArea()).thenReturn(null); // fall back to 16-bit clamping only
        SessionBox box = sessionBoxMap.computeIfAbsent(session, s -> new SessionBox());
        doAnswer(invocation -> {
            InPacket packet = invocation.getArgument(1);
            packet.skip(HEADER_BYTES);
            packet.skip(1); // numCommands
            packet.skip(1); // command type (0 = absolute)
            box.emitted[0] = new Point(packet.readShort(), packet.readShort());
            packet.skip(6); // xwobble, ywobble, foothold
            stances.add((int) packet.readByte());
            return true;
        }).when(session).dispatchInbound(Mockito.eq(RecvOpcode.MOVE_PLAYER), any(InPacket.class));
        return session;
    }

    // -----------------------------------------------------------------------------------------
    // Real WZ loading (mirrors server.maps.MapFactory / AgentMovementWzRuntimeTest)
    // -----------------------------------------------------------------------------------------

    private static Path locateMapWz() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty("wz-path");
        if (explicit != null) {
            candidates.add(Path.of(explicit, "Map.wz"));
        }
        Path base = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = base; p != null; p = p.getParent()) {
            candidates.add(p.resolve("wz").resolve("Map.wz"));
        }
        candidates.add(Path.of("wz", "Map.wz"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String mapName(int mapId) {
        return String.format("Map/Map%d/%09d.img", mapId / 100000000, mapId);
    }

    private static FootholdTree loadFootholds(int mapId) {
        Data mapData = mapSource.getData(mapName(mapId));
        if (mapData == null) {
            return null;
        }
        Data footRoot = mapData.getChildByPath("foothold");
        if (footRoot == null) {
            return null;
        }
        List<Foothold> all = new ArrayList<>();
        Point lBound = new Point();
        Point uBound = new Point();
        for (Data footRootCat : footRoot) {
            for (Data footCat : footRootCat) {
                for (Data footHold : footCat) {
                    int x1 = DataTool.getInt(footHold.getChildByPath("x1"));
                    int y1 = DataTool.getInt(footHold.getChildByPath("y1"));
                    int x2 = DataTool.getInt(footHold.getChildByPath("x2"));
                    int y2 = DataTool.getInt(footHold.getChildByPath("y2"));
                    Foothold fh = new Foothold(new Point(x1, y1), new Point(x2, y2),
                            Integer.parseInt(footHold.getName()));
                    if (fh.getX1() < lBound.x) {
                        lBound.x = fh.getX1();
                    }
                    if (fh.getX2() > uBound.x) {
                        uBound.x = fh.getX2();
                    }
                    if (fh.getY1() < lBound.y) {
                        lBound.y = fh.getY1();
                    }
                    if (fh.getY2() > uBound.y) {
                        uBound.y = fh.getY2();
                    }
                    all.add(fh);
                }
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        FootholdTree tree = new FootholdTree(lBound, uBound);
        for (Foothold fh : all) {
            tree.insert(fh);
        }
        return tree;
    }

    /** Parses the map's {@code ladderRope} entries directly from the WZ XML. */
    private static List<AgentPathing.Ladder> parseLadders(int mapId) {
        Data mapData = mapSource.getData(mapName(mapId));
        if (mapData == null) {
            return List.of();
        }
        Data root = mapData.getChildByPath("ladderRope");
        if (root == null) {
            return List.of();
        }
        List<AgentPathing.Ladder> ladders = new ArrayList<>();
        for (Data entry : root) {
            ladders.add(new AgentPathing.Ladder(
                    DataTool.getInt(entry.getChildByPath("x"), 0),
                    DataTool.getInt(entry.getChildByPath("y1"), 0),
                    DataTool.getInt(entry.getChildByPath("y2"), 0),
                    DataTool.getInt(entry.getChildByPath("l"), 0) == 1,
                    DataTool.getInt(entry.getChildByPath("uf"), 0) == 1,
                    DataTool.getInt(entry.getChildByPath("page"), 0)));
        }
        return ladders;
    }
}
