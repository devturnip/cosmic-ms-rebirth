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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tier-2 pathfinding falsification (hand-off §4.20): limbo recovery on slopes, physics-validated
 * jump arcs that reject platform-clipping routes, the cached nav graph (correctness +
 * thread-safety), navigation completeness on real PQ maps, and the per-session manoeuvre state
 * completing a walk-to-takeoff gap jump the old re-pick pathfinder failed.
 *
 * <p>Real-map tests skip gracefully (via {@code assumeTrue}) when the WZ tree is unavailable,
 * mirroring {@code AgentMovementWzRuntimeTest}.
 */
class AgentPathingTier2Test {
    private static final int HEADER_BYTES = 9;
    private static final int MAX_STEP = AgentMovement.MAX_STEP;

    /** Real KPQ/Ellin stage maps: jumps, ladders and slopes, all loadable from wz/Map.wz. */
    private static final List<Integer> Pq_MAPS = List.of(
            103000801, // KPQ stage 1
            103000802, // KPQ stage 2 (cloud platforms, gap jumps)
            103000803, // KPQ stage 3
            930000500); // Ellin PQ main area
    private static final int NAV_SAMPLES_PER_MAP = 60;
    private static final long SEED = 0x5EED_1234L;

    private static XMLWZFile mapSource;

    @BeforeAll
    static void loadMapWz() {
        Path mapWz = locateMapWz();
        if (mapWz != null) {
            mapSource = new XMLWZFile(mapWz);
        }
    }

    // -----------------------------------------------------------------------------------------
    // (f) §4.20 known issue: oscillation on 103000802 target (1100,-75)
    // -----------------------------------------------------------------------------------------

    @Test
    void midAirTargetAboveAQuirkResolvedSlopeSettlesWithoutOscillation() throws Exception {
        // The §4.20 known issue: MoveTo (1100,-75) on KPQ 103000802 from the -135 cloud chain
        // cycled the agent between the -135/-15/-75 levels forever, via repeated 120px
        // jump-stance (fall-stance) moves (1100,-135) → (1100,-15). The connector slope
        // fh 50 (1080,-15)→(1170,-75) resolves through the server's integer-division
        // calculateFooting as a FLAT platform at y=-15 spanning x=1080..1170, so (1100,-75) is
        // mid-air: the real -135 cloud (fh 9) and the quirk-resolved -15 surface tie at exactly
        // 60px from the target. The direct step must not drop the agent onto the phantom surface
        // past the target (the router already considers the agent arrived), and no emitted hop
        // may exceed what v83 physics allows (apex ≈77px).
        assumeWz();
        int mapId = 103000802;
        FootholdTree tree = loadFootholds(mapId);
        assumeTree(tree);
        AgentNavGraph graph = AgentNavGraph.build(tree, parseLadders(mapId));
        assertNotNull(graph);
        AgentNavGraph.putGraph(mapId, graph);
        AgentPathing.putLadders(mapId, parseLadders(mapId));

        int[] position = {1095, -135}; // the -135 cloud chain (fh 9: x 1047..1113)
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statefulSession(tree, mapId, position, stances);

        DriveResult result = driveUntilQuiet(session, position, new Point(1100, -75), 200, 50);

        assertTrue(result.quietTicks() >= 0,
                "the agent must stop emitting steps (no oscillation), final=" + position
                        + " steps=" + result.steps());
        assertTrue(result.steps() <= 10,
                "settlement must be quick (the -135 cloud is already the tied goal), steps="
                        + result.steps());
        // Settled grounded on a real server-resolved surface at the target column.
        assertEquals(1100, position[0], "the agent must hold the target column");
        Foothold support = supportingFoothold(tree, new Point(position[0], position[1]));
        assertNotNull(support, "the agent must end grounded, got " + position[0] + "," + position[1]);
        assertEquals(-135, position[1],
                "the agent must settle on the tied nearest surface (the -135 cloud), got "
                        + position[1]);
        // No emitted step may lift the agent beyond the v83 jump apex.
        assertNoImpossibleLift(result, -135, stances);
    }

    @Test
    void ropeClimbFallRecoveryTargetStillCompletesOn103000802() throws Exception {
        // The other documented-correct target on the same map: the rope/jump climb down to
        // (719,-236) with mid-route fall recovery must still complete.
        assumeWz();
        int mapId = 103000802;
        FootholdTree tree = loadFootholds(mapId);
        assumeTree(tree);
        AgentNavGraph graph = AgentNavGraph.build(tree, parseLadders(mapId));
        assertNotNull(graph);
        AgentNavGraph.putGraph(mapId, graph);
        AgentPathing.putLadders(mapId, parseLadders(mapId));

        Point spawn = spawnPoint(mapId, tree);
        assertNotNull(spawn);
        int[] position = {spawn.x, spawn.y};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statefulSession(tree, mapId, position, stances);

        DriveResult result = driveUntilQuiet(session, position, new Point(719, -236), 400, 50);

        assertEquals(719, position[0], "the agent must reach the target column");
        assertEquals(-236, position[1], "the agent must reach the server-resolved target surface");
        assertNotNull(supportingFoothold(tree, new Point(position[0], position[1])),
                "the agent must end grounded, got " + position[0] + "," + position[1]);
        assertTrue(result.quietTicks() >= 0, "the agent must terminate, final=" + position);
        assertNoImpossibleLift(result, spawn.y, stances);
    }

    /** Drive outcome: the emitted steps, the quiet-tick count (-1 = never settled), total steps. */
    private record DriveResult(List<Point> path, int quietTicks, int steps) {
    }

    /**
     * Drives up to {@code maxTicks} MoveTo ticks, stopping once {@code quietTicks} consecutive
     * ticks emitted no step (settled).
     */
    private static DriveResult driveUntilQuiet(AgentSession session, int[] position, Point target,
            int maxTicks, int quietTicks) {
        List<Point> path = new ArrayList<>();
        Point[] emitted = emittedBox(session);
        int quiet = 0;
        for (int i = 0; i < maxTicks && quiet < quietTicks; i++) {
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
                quiet = 0;
            } else {
                quiet++;
            }
        }
        return new DriveResult(path, quiet >= quietTicks ? quiet : -1, path.size());
    }

    /** No emitted step may lift the agent more than the v83 jump apex (climb steps exempt). */
    private static void assertNoImpossibleLift(DriveResult drive, int startY, List<Integer> stances) {
        int prevY = startY;
        List<Point> path = drive.path();
        for (int i = 0; i < path.size(); i++) {
            Point p = path.get(i);
            int lift = prevY - p.y; // > 0 when the step rose
            boolean climb = stances.size() > i && stances.get(i) == AgentMovement.CLIMB_STANCE;
            assertTrue(climb || lift <= (int) AgentPhysics.JUMP_APEX_PX + 1,
                    "emitted a lift of " + lift + "px (apex "
                            + (int) AgentPhysics.JUMP_APEX_PX + ") at step " + i + " -> " + p);
            prevY = p.y;
        }
    }

    // -----------------------------------------------------------------------------------------
    // (a) Limbo recovery on slopes
    // -----------------------------------------------------------------------------------------

    @Test
    void agentSlippedBelowASlopeWalksBackOntoTheSurface() {
        // A steep slope from (0,0) down to (100,-200); the agent sits 20px BELOW its surface at
        // x=50 (surface y=-100, agent y=-80) — the grounded-but-unroutable limbo. (Note: the
        // server's own integer-division calculateFooting only resolves slopes steeper than 1:1,
        // so the test uses a genuinely steep foothold.)
        FootholdTree tree = treeWith(new Foothold(new Point(0, 0), new Point(100, -200), 1));
        Foothold slope = tree.getAllFootholds().get(0);
        int[] position = {50, -80};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statelessSession(tree, 987200, position, stances);

        // The slip is still grounded (footholdAt's recovery window) ...
        assertNotNull(AgentMovement.footholdAt(tree, new Point(50, -80)),
                "an agent 20px below a slope must still classify as grounded");

        List<Point> path = drive(session, position, new Point(75, -150), 4);

        assertFalse(path.isEmpty(), "the agent must not freeze in limbo");
        int x = position[0];
        assertEquals(slope.calculateFooting(x), position[1],
                "the agent must end snapped onto the slope surface at its column");
        assertFalse(stances.contains(AgentMovement.CLIMB_STANCE),
                "a slope slip is not a rope climb, stances=" + stances);
    }

    @Test
    void everyHorizontalStepIsSnappedToItsSupportSurface() {
        // Phase-1 invariant: walking below a surface first snaps straight up (x unchanged), and
        // every subsequent walk step ends exactly on the surface under its destination column.
        FootholdTree tree = treeWith(new Foothold(new Point(0, 0), new Point(100, -200), 1));
        Foothold slope = tree.getAllFootholds().get(0);
        int[] position = {50, -80};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statelessSession(tree, 987203, position, stances);

        List<Point> path = drive(session, position, new Point(95, -190), 4);

        assertFalse(path.isEmpty());
        for (Point p : path) {
            assertEquals(slope.calculateFooting(p.x), p.y,
                    "every step must end on the slope surface, got " + p);
        }
    }

    @Test
    void gravityFallsDoNotUseTheClimbStance() {
        // An airborne agent above a plain platform: the fall must keep an air stance, not 16.
        FootholdTree tree = treeWith(new Foothold(new Point(0, 200), new Point(400, 200), 1));
        int[] position = {100, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statelessSession(tree, 987202, position, stances);

        drive(session, position, new Point(100, 200), 3);

        assertFalse(stances.isEmpty(), "a fall step should be emitted");
        assertFalse(stances.contains(AgentMovement.CLIMB_STANCE),
                "gravity falls must not reuse the climb stance 16, stances=" + stances);
    }

    // -----------------------------------------------------------------------------------------
    // (b) Physics-validated jump arcs reject platform-clipping routes
    // -----------------------------------------------------------------------------------------

    @Test
    void jumpArcThatClipsAnInterveningPlatformIsRejected() {
        // A [0,100] y=0, M [150,200] y=-40 (juts into the gap, above takeoff level),
        // B [165,350] y=0. The flat-jump arc from A's edge descends through M's surface
        // (x≈158 at y=-40) before it could land on B (x≈171) — the direct A→B arc must be
        // rejected. Hopping onto the obstruction itself (A→M→B) is legitimate and may be routed
        // instead, but no jump may overfly M straight to B.
        Foothold a = new Foothold(new Point(0, 0), new Point(100, 0), 1);
        Foothold m = new Foothold(new Point(150, -40), new Point(200, -40), 2);
        Foothold b = new Foothold(new Point(165, 0), new Point(350, 0), 3);
        FootholdTree blocked = treeWith(a, m, b);
        FootholdTree clear = treeWith(a, b);

        AgentNavGraph withM = AgentNavGraph.build(blocked, List.of());
        assertNotNull(withM, "the map is small enough to graph");
        // Without the intervening platform the jump lands on B.
        AgentNavGraph withoutM = AgentNavGraph.build(clear, List.of());
        assertNotNull(withoutM);

        List<AgentPathing.Hop> route = withM.findRoute(new Point(50, 0), a, new Point(300, 0));
        if (route != null) {
            boolean touchedObstruction = false;
            for (AgentPathing.Hop hop : route) {
                if (hop.type() == AgentPathing.HopType.JUMP && !touchedObstruction) {
                    assertEquals(-40, hop.destY(),
                            "the first jump must land on the obstruction, not arc over it to B");
                }
                if (hop.destY() == -40) {
                    touchedObstruction = true;
                }
            }
            assertTrue(touchedObstruction, "a routed path must cross the obstruction, hops=" + route);
        }
        List<AgentPathing.Hop> clearRoute =
                withoutM.findRoute(new Point(50, 0), a, new Point(300, 0));
        assertNotNull(clearRoute, "without the obstruction the gap is crossable");
        assertTrue(clearRoute.stream().anyMatch(h -> h.type() == AgentPathing.HopType.JUMP),
                "the clear route uses a jump, hops=" + clearRoute);
    }

    @Test
    void flatGapsBeyondTheJumpReachAreNotRouted() {
        // Two same-height platforms 100px apart: a v83 flat jump covers only ~69px, so the old
        // magic 120px gap is physically impossible and must not be routed.
        FootholdTree tree = treeWith(
                new Foothold(new Point(0, 0), new Point(100, 0), 1),
                new Foothold(new Point(200, 0), new Point(400, 0), 2));
        AgentNavGraph graph = AgentNavGraph.build(tree, List.of());
        assertNotNull(graph);
        assertNull(graph.findRoute(new Point(50, 0),
                tree.getAllFootholds().get(0), new Point(300, 0)),
                "a 100px flat gap exceeds the v83 jump reach (~69px)");
    }

    @Test
    void jumpApexAndRangeDeriveFromTheWzPhysics() {
        // gravity 2000, jumpSpeed 555, walkSpeed 125 (wz/Map.wz/Physics.img.xml).
        assertEquals(77.0, AgentPhysics.JUMP_APEX_PX, 0.5, "apex = v0²/2g");
        assertEquals(69.0, AgentPhysics.JUMP_FLAT_RANGE_PX, 0.5, "range = walkSpeed · 2v0/g");
        // A 150px drop extends the airtime and therefore the reach (~94px).
        assertTrue(AgentPhysics.jumpRangeForDrop(150) > AgentPhysics.JUMP_FLAT_RANGE_PX);
        assertEquals(AgentMovement.MAX_STEP, AgentPhysics.MAX_JUMP_DROP_PX,
                "jumps never exceed the per-tick clamp vertically");
    }

    // -----------------------------------------------------------------------------------------
    // (c) Nav-graph cache correctness + thread-safety
    // -----------------------------------------------------------------------------------------

    @Test
    void graphCacheIsThreadSafeAndReturnsTheSameInstance() throws Exception {
        assumeWz();
        int mapId = 103000801;
        FootholdTree tree = loadFootholds(mapId);
        assumeTree(tree);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<AgentNavGraph>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                // First call warms asynchronously (returns null); poll until it is ready.
                AgentNavGraph graph = AgentNavGraph.forMap(mapId, tree);
                long deadline = System.currentTimeMillis() + 10_000;
                while (graph == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                    graph = AgentNavGraph.forMap(mapId, tree);
                }
                return graph;
            }));
        }
        start.countDown();
        AgentNavGraph first = null;
        for (var future : futures) {
            AgentNavGraph graph = future.get(20, TimeUnit.SECONDS);
            assertNotNull(graph, "every racer must eventually see the warmed graph");
            assertFalse(graph.declined(), "a real PQ map must be graphable");
            if (first == null) {
                first = graph;
            } else {
                assertSame(first, graph, "all racers must share one cached instance");
            }
        }
        pool.shutdownNow();
        assertSame(first, AgentNavGraph.forMap(mapId, tree), "the cache must stick");
        assertTrue(first.nodeCount() > 0);
    }

    // -----------------------------------------------------------------------------------------
    // (d) Navigation completeness on real PQ maps
    // -----------------------------------------------------------------------------------------

    @Test
    void realPqMapsAreNavigableFromTheirSpawnPoint() throws Exception {
        assumeWz();
        // Per-map reachability floors. KPQ stage maps are fully connected by foot/jumps/ropes;
        // Ellin (930000500) is a vertical PQ map whose upper stages connect via PORTALS (not
        // traversable geometry), so a large share of its stand points is legitimately unreachable
        // from the spawn — the low floor documents that instead of hiding it.
        java.util.Map<Integer, Double> floors = java.util.Map.of(
                103000801, 0.9,
                103000802, 0.8,
                103000803, 0.9,
                930000500, 0.3);
        for (int mapId : Pq_MAPS) {
            FootholdTree tree = loadFootholds(mapId);
            assertNotNull(tree, "footholds for " + mapId);
            Point spawn = spawnPoint(mapId, tree);
            assertNotNull(spawn, "spawn portal for " + mapId);

            AgentNavGraph graph = AgentNavGraph.build(tree, parseLadders(mapId));
            assertNotNull(graph, "map " + mapId + " must be graphable");
            Foothold spawnFh = supportingFoothold(tree, spawn);

            // Edge census (printed for the report): manoeuvre counts per type.
            java.util.EnumMap<AgentPathing.HopType, Integer> edgeTypes =
                    new java.util.EnumMap<>(AgentPathing.HopType.class);
            graph.forEachEdge(hop -> edgeTypes.merge(hop.type(), 1, Integer::sum));

            Random rng = new Random(SEED ^ mapId);
            List<Point> targets = sampleStandPoints(rng, tree, NAV_SAMPLES_PER_MAP);
            int reachable = 0;
            List<Point> unreachable = new ArrayList<>();
            for (Point target : targets) {
                List<AgentPathing.Hop> route = graph.findRoute(spawn, spawnFh, target);
                if (route != null) {
                    reachable++;
                } else {
                    unreachable.add(target);
                }
            }
            System.out.printf("[AgentPathingTier2Test] map=%d nodes=%d edges=%s targets=%d reachable=%d unreachable=%d%n",
                    mapId, graph.nodeCount(), edgeTypes, targets.size(), reachable, unreachable.size());
            double floor = floors.get(mapId);
            assertTrue(reachable >= targets.size() * floor,
                    "map " + mapId + ": only " + reachable + "/" + targets.size()
                            + " stand points reachable from spawn (floor " + floor + "), unreachable="
                            + unreachable);
        }
    }

    @Test
    void realMapGraphRoutesExecuteThroughMoveToSmart() throws Exception {
        assumeWz();
        int mapId = 103000802; // KPQ stage 2: gap jumps between cloud platforms
        FootholdTree tree = loadFootholds(mapId);
        assumeTree(tree);
        AgentNavGraph graph = AgentNavGraph.build(tree, parseLadders(mapId));
        assertNotNull(graph);
        AgentNavGraph.putGraph(mapId, graph);
        AgentPathing.putLadders(mapId, parseLadders(mapId));

        Point spawn = spawnPoint(mapId, tree);
        assertNotNull(spawn);
        Foothold spawnFh = supportingFoothold(tree, spawn);

        // Drive to the right-most stand point the graph can actually reach from the spawn (the
        // farthest column overall may sit behind portal-only progression).
        Random rng = new Random(SEED ^ mapId);
        Point best = null;
        int bestHops = 0;
        for (Point candidate : sampleStandPoints(rng, tree, 200)) {
            List<AgentPathing.Hop> route = graph.findRoute(spawn, spawnFh, candidate);
            if (route != null && !route.isEmpty() && (best == null || candidate.x > best.x)) {
                best = candidate;
                bestHops = route.size();
            }
        }
        assertNotNull(best, "the graph must reach somewhere from the spawn");
        System.out.printf("[AgentPathingTier2Test] map=%d drivenTarget=%s hops=%d%n", mapId, best, bestHops);
        Foothold destFh = supportingFoothold(tree, best);
        assertNotNull(destFh);

        int[] position = {spawn.x, spawn.y};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statelessSession(tree, mapId, position, stances);

        List<Point> path = drive(session, position, best, 120);

        Foothold support = supportingFoothold(tree, new Point(position[0], position[1]));
        assertNotNull(support, "the agent must end on a real foothold, got " + position);
        assertEquals(destFh, support, "the agent must reach the routed platform via the graph");
        assertEquals(best.x, position[0], "the agent must reach the routed column");
        assertTrue(stances.contains(AgentMovement.JUMP_STANCE_RIGHT)
                        || stances.contains(AgentMovement.JUMP_STANCE_LEFT)
                        || stances.contains(AgentMovement.CLIMB_STANCE),
                "cross-map progress must use a real manoeuvre, stances=" + stances);
        for (Point p : path) {
            assertTrue(Math.abs(p.x) <= Short.MAX_VALUE && Math.abs(p.y) <= Short.MAX_VALUE);
        }
    }

    // -----------------------------------------------------------------------------------------
    // (e) Per-session manoeuvre state: walk-to-takeoff then jump
    // -----------------------------------------------------------------------------------------

    @Test
    void statefulManoeuvreCompletesAWalkToTakeoffGapJumpTheOldPathfinderFailed() {
        // A [0,200] y=0 and B [250,450] y=-40: a 50px gap onto a platform 40px higher. The old
        // pathfinder required a gap-jump rise within JUMP_LEVEL_TOLERANCE=30 → no route, the
        // agent stalled at the edge. The v83 arc (apex 77) clears it: walk to the takeoff
        // sample, jump, land on B, walk to the target.
        Foothold a = new Foothold(new Point(0, 0), new Point(200, 0), 1);
        Foothold b = new Foothold(new Point(250, -40), new Point(450, -40), 2);
        FootholdTree tree = treeWith(a, b);

        int mapId = 987300;
        AgentPathing.putLadders(mapId, List.of());
        AgentNavGraph.putGraph(mapId, AgentNavGraph.build(tree, List.of()));

        int[] position = {50, 0};
        List<Integer> stances = new ArrayList<>();
        AgentSession session = statefulSession(tree, mapId, position, stances);

        List<Point> path = drive(session, position, new Point(400, -40), 10);

        assertFalse(path.isEmpty(), "the agent must move");
        assertTrue(position[1] == -40 && position[0] >= 250 && position[0] <= 450,
                "the agent must land on the higher platform across the gap, got " + position
                        + " path=" + path);
        assertTrue(stances.contains(AgentMovement.JUMP_STANCE_RIGHT),
                "the manoeuvre must include a real jump, stances=" + stances);
        // Walk-to-takeoff: some grounded step on A happened before the jump.
        assertTrue(path.stream().anyMatch(p -> p.y == 0 && p.x > 100 && p.x <= 200),
                "the agent must walk toward the takeoff point before jumping, path=" + path);
        for (Point p : path) {
            assertTrue(onSurface(tree, p, 8) || inJumpTransit(p, path),
                    "agent left the platforms: " + p + " path=" + path);
        }
    }

    @Test
    void statefulManoeuvreIsInvalidatedByANewTarget() {
        FootholdTree tree = treeWith(new Foothold(new Point(0, 0), new Point(500, 0), 1));
        int mapId = 987301;
        AgentPathing.putLadders(mapId, List.of());

        AgentMovementState state = new AgentMovementState();
        List<AgentPathing.Hop> route = List.of(new AgentPathing.Hop(AgentPathing.HopType.WALK,
                0, 0, 400, 0, null, false));
        state.start(route, new Point(400, 0), mapId);
        assertTrue(state.hasRoute());

        // Same target on the same map: survives.
        assertTrue(state.validate(mapId, new Point(410, 0)));
        assertTrue(state.hasRoute());
        // A far-off new target (a real re-task, not follow jitter): cleared.
        assertFalse(state.validate(mapId, new Point(2000, 0)));
        assertFalse(state.hasRoute());
        // A map change (follow warp / portal): cleared.
        state.start(route, new Point(400, 0), mapId);
        assertFalse(state.validate(mapId + 1, new Point(400, 0)));
        assertFalse(state.hasRoute());
    }

    // -----------------------------------------------------------------------------------------
    // Simulation helpers
    // -----------------------------------------------------------------------------------------

    private static boolean onSurface(FootholdTree tree, Point p, int tolerance) {
        return supportingFoothold(tree, p) != null;
    }

    /** Jump transits are single absolute moves from one platform to the next; only the endpoints exist. */
    private static boolean inJumpTransit(Point p, List<Point> path) {
        return path.contains(p);
    }

    /** The non-wall foothold supporting {@code p} (surface within 8px), or {@code null}. */
    private static Foothold supportingFoothold(FootholdTree tree, Point p) {
        Foothold best = null;
        int bestGap = 9;
        for (Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            if (p.x < Math.min(fh.getX1(), fh.getX2()) || p.x > Math.max(fh.getX1(), fh.getX2())) {
                continue;
            }
            int gap = Math.abs(fh.calculateFooting(p.x) - p.y);
            if (gap < bestGap) {
                best = fh;
                bestGap = gap;
            }
        }
        return best;
    }

    /** Drives up to {@code ticks} steps, asserting the per-tick clamp invariant after each. */
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

    private static final java.util.Map<AgentSession, Point[]> SESSION_BOXES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Point[] emittedBox(AgentSession session) {
        return SESSION_BOXES.computeIfAbsent(session, s -> new Point[1]);
    }

    /** A mocked stateless session (legacy per-tick routing, like the existing movement tests). */
    private static AgentSession statelessSession(FootholdTree tree, int mapId, int[] position,
            List<Integer> stances) {
        return mockedSession(tree, mapId, position, stances, null);
    }

    /** A mocked session carrying a real per-session manoeuvre state (Tier-2 routing). */
    private static AgentSession statefulSession(FootholdTree tree, int mapId, int[] position,
            List<Integer> stances) {
        return mockedSession(tree, mapId, position, stances, new AgentMovementState());
    }

    private static AgentSession mockedSession(FootholdTree tree, int mapId, int[] position,
            List<Integer> stances, AgentMovementState state) {
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        if (state != null) {
            when(session.movementState()).thenReturn(state);
        }
        java.util.concurrent.atomic.AtomicBoolean climbing =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        when(session.isClimbingRope()).thenAnswer(inv -> climbing.get());
        doAnswer(inv -> {
            climbing.set(inv.getArgument(0));
            return null;
        }).when(session).setClimbingRope(Mockito.anyBoolean());
        when(chr.isFacingLeft()).thenReturn(false);
        when(chr.getPosition()).thenAnswer(invocation -> new Point(position[0], position[1]));
        when(chr.getMap()).thenReturn(map);
        when(map.getId()).thenReturn(mapId);
        when(map.getFootholds()).thenReturn(tree);
        when(map.getMapArea()).thenReturn(null);
        Point[] box = emittedBox(session);
        doAnswer(invocation -> {
            InPacket packet = invocation.getArgument(1);
            packet.skip(HEADER_BYTES);
            packet.skip(1); // numCommands
            packet.skip(1); // command type (0 = absolute, read first)
            box[0] = new Point(packet.readShort(), packet.readShort());
            packet.skip(6); // xwobble, ywobble, foothold
            stances.add((int) packet.readByte());
            return true;
        }).when(session).dispatchInbound(Mockito.eq(RecvOpcode.MOVE_PLAYER), any(InPacket.class));
        return session;
    }

    private static FootholdTree treeWith(Foothold... footholds) {
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(5000, 5000), 0);
        for (Foothold foothold : footholds) {
            tree.insert(foothold);
        }
        return tree;
    }

    // -----------------------------------------------------------------------------------------
    // Real WZ loading (mirrors server.maps.MapFactory / AgentMovementWzRuntimeTest)
    // -----------------------------------------------------------------------------------------

    private static void assumeWz() {
        org.junit.jupiter.api.Assumptions.assumeTrue(mapSource != null, "WZ Map.wz unavailable");
    }

    private static void assumeTree(FootholdTree tree) {
        org.junit.jupiter.api.Assumptions.assumeTrue(tree != null, "footholds unavailable");
    }

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
        Data infoData = mapData.getChildByPath("info");
        if (infoData != null) {
            String link = DataTool.getString(infoData.getChildByPath("link"), "");
            if (!link.isEmpty()) {
                Data linked = mapSource.getData(mapName(Integer.parseInt(link)));
                if (linked != null) {
                    mapData = linked;
                }
            }
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

    /** The position of the map's default spawn portal ({@code pn="sp"}), snapped to its surface. */
    private static Point spawnPoint(int mapId, FootholdTree tree) {
        Data mapData = mapSource.getData(mapName(mapId));
        if (mapData == null) {
            return null;
        }
        Data infoData = mapData.getChildByPath("info");
        if (infoData != null) {
            String link = DataTool.getString(infoData.getChildByPath("link"), "");
            if (!link.isEmpty()) {
                Data linked = mapSource.getData(mapName(Integer.parseInt(link)));
                if (linked != null) {
                    mapData = linked;
                }
            }
        }
        Data portals = mapData.getChildByPath("portal");
        if (portals == null) {
            return null;
        }
        for (Data portal : portals) {
            if ("sp".equals(DataTool.getString(portal.getChildByPath("pn"), ""))) {
                int x = DataTool.getInt(portal.getChildByPath("x"), 0);
                int y = DataTool.getInt(portal.getChildByPath("y"), 0);
                Foothold below = tree.findBelow(new Point(x, y + 1));
                if (below != null) {
                    return new Point(x, below.calculateFooting(x));
                }
                return new Point(x, y);
            }
        }
        return null;
    }

    /** Samples {@code n} stand points on random non-wall foothold surfaces. */
    private static List<Point> sampleStandPoints(Random rng, FootholdTree tree, int n) {
        List<Foothold> platforms = new ArrayList<>();
        for (Foothold fh : tree.getAllFootholds()) {
            if (!fh.isWall() && Math.max(fh.getX1(), fh.getX2()) > Math.min(fh.getX1(), fh.getX2())) {
                platforms.add(fh);
            }
        }
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < n && !platforms.isEmpty(); i++) {
            Foothold fh = platforms.get(rng.nextInt(platforms.size()));
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            int x = minX + rng.nextInt(maxX - minX + 1);
            points.add(new Point(x, fh.calculateFooting(x)));
        }
        return points;
    }
}
