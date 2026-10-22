package net.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-map cached navigation graph over <em>stand points</em> (Phase 3 of the Tier-2 pathfinding
 * rework, hand-off §4.20). Nodes are points sampled every ~15px along each non-wall foothold with
 * {@code y} snapped to {@link Foothold#calculateFooting(int)}; edges are:
 * <ul>
 *   <li><b>walk</b> — consecutive samples on a foothold, plus welds where two footholds share an
 *       endpoint or overlap at (near-)equal height (cost: distance / walk speed);</li>
 *   <li><b>fall</b> — straight down onto the surface below a sample (cost: physics fall time);</li>
 *   <li><b>jump</b> — a v83 jump arc validated by {@link AgentPhysics#simulateJump} from a snapped
 *       takeoff sample onto the target foothold's surface (cost: airtime); arcs that clip an
 *       intervening platform are rejected at build time;</li>
 *   <li><b>climb</b> — a usable WZ {@code ladderRope} between a grabbable and a dismountable
 *       foothold (cost: climb time).</li>
 * </ul>
 *
 * <p>The graph is built lazily per map id on first use (after that map's {@code ladderRope} data
 * has warmed), cached statically and shared by all agents — the per-tick cost of routing drops to
 * a single A* over precomputed edges instead of rebuilding a foothold BFS every tick. Maps whose
 * geometry would exceed {@link #MAX_NODES} stand points are <b>declined</b> (a sentinel instance
 * is cached so the failed build is not retried every tick); those maps fall back to the legacy
 * bounded BFS in {@link AgentPathing}. Like the ladder cache, a cold graph warms asynchronously
 * and simply reports "no route" for the first tick.
 */
final class AgentNavGraph {
    private static final Logger log = LoggerFactory.getLogger(AgentNavGraph.class);

    /** Stand-point spacing (px) along a foothold. */
    static final int SAMPLE_PX = 15;
    /** Node cap: maps needing more stand points are declined (fall back to the legacy BFS). */
    static final int MAX_NODES = 4000;
    /** Max height difference (px) between two welded (walkable) foothold boundaries. */
    static final int WELD_TOLERANCE_PX = 6;
    /** Longest drop modelled as a fall edge (anything longer is not worth routing). */
    static final int MAX_DROP_PX = 2000;
    /** Rough vertical climb speed used to cost rope climbs (px/s). */
    private static final double CLIMB_SPEED_PX_PER_S = 125.0;

    /** Sentinel cached for maps whose geometry is too large to graph (or that failed to build). */
    private static final AgentNavGraph DECLINED = new AgentNavGraph();

    private static final Map<Integer, AgentNavGraph> CACHE = new ConcurrentHashMap<>();
    private static final Set<Integer> WARMING = ConcurrentHashMap.newKeySet();

    /** A graph node: a snapped stand point on a specific foothold. */
    private record Node(int x, int y, Foothold fh) {
    }

    /** A directed edge: precomputed manoeuvre + traversal cost (seconds). */
    private record Edge(int to, double cost, AgentPathing.Hop hop) {
    }

    private final List<Node> nodes;
    private final List<List<Edge>> adjacency;

    private AgentNavGraph() {
        this.nodes = List.of();
        this.adjacency = List.of();
    }

    private AgentNavGraph(List<Node> nodes, List<List<Edge>> adjacency) {
        this.nodes = nodes;
        this.adjacency = adjacency;
    }

    /** Whether this instance is the cached "map declined" sentinel. */
    boolean declined() {
        return this == DECLINED;
    }

    int nodeCount() {
        return nodes.size();
    }

    /** Test/diagnostic hook: visits every edge's precomputed manoeuvre. */
    void forEachEdge(java.util.function.Consumer<AgentPathing.Hop> visitor) {
        for (List<Edge> edges : adjacency) {
            for (Edge edge : edges) {
                visitor.accept(edge.hop());
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Construction & caching (mirrors the LADDER_CACHE warmAsync pattern)
    // -----------------------------------------------------------------------------------------

    /**
     * Returns the cached graph for {@code mapId}, or {@code null} while it is still warming (the
     * caller should fall back to the legacy BFS for that tick). The {@code tree} is captured on
     * the first request; geometry for a given map id is identical across channels.
     */
    static AgentNavGraph forMap(int mapId, FootholdTree tree) {
        AgentNavGraph cached = CACHE.get(mapId);
        if (cached != null) {
            return cached;
        }
        warmAsync(mapId, tree);
        return null;
    }

    /** Seeds/overrides the graph cache (used by tests; never blocks). */
    static void putGraph(int mapId, AgentNavGraph graph) {
        CACHE.put(mapId, graph);
    }

    /** Test hook: drops all cached graphs. */
    static void clearCacheForTests() {
        CACHE.clear();
    }

    private static void warmAsync(int mapId, FootholdTree tree) {
        if (!WARMING.add(mapId)) {
            return; // a warm build is already in flight for this map
        }
        final FootholdTree captured = tree;
        final Thread builder = new Thread(() -> {
            try {
                CACHE.computeIfAbsent(mapId, id -> {
                    List<AgentPathing.Ladder> ladders =
                            AgentPathing.laddersForGraph(id);
                    AgentNavGraph built = build(captured, ladders);
                    return built == null ? DECLINED : built;
                });
                log.debug("Agent nav graph for map {} ready (nodes={})",
                        mapId, CACHE.get(mapId).nodeCount());
            } catch (Throwable t) {
                log.debug("Could not build agent nav graph for map {}: {}", mapId, t.toString());
                CACHE.put(mapId, DECLINED);
            } finally {
                WARMING.remove(mapId);
            }
        }, "agent-navgraph-warm-" + mapId);
        builder.setDaemon(true);
        builder.start();
    }

    /**
     * Builds the graph for a map, or {@code null} when the map is too large to graph. Pure
     * geometry + ladder data; no per-agent state.
     */
    static AgentNavGraph build(FootholdTree tree, List<AgentPathing.Ladder> ladders) {
        List<Foothold> platforms = new ArrayList<>();
        for (Foothold fh : tree.getAllFootholds()) {
            if (!fh.isWall()) {
                platforms.add(fh);
            }
        }
        List<Node> nodes = new ArrayList<>();
        Map<Foothold, List<Integer>> byFoothold = new IdentityHashMap<>();
        for (Foothold fh : platforms) {
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (maxX <= minX) {
                continue; // degenerate sliver: nothing to stand on
            }
            List<Integer> ids = new ArrayList<>();
            for (int x = minX; x <= maxX && nodes.size() < MAX_NODES; x += SAMPLE_PX) {
                ids.add(addNode(nodes, x, fh));
            }
            // Always keep an endpoint sample at the foothold's far edge (jump takeoffs).
            int lastX = nodes.get(ids.get(ids.size() - 1)).x();
            if (nodes.size() < MAX_NODES && maxX - lastX >= 4) {
                ids.add(addNode(nodes, maxX, fh));
            }
            if (nodes.size() >= MAX_NODES) {
                return null; // over the cap: decline the whole graph
            }
            byFoothold.put(fh, ids);
        }
        if (nodes.isEmpty()) {
            return null;
        }
        List<List<Edge>> adjacency = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            adjacency.add(new ArrayList<>());
        }
        addWalkEdges(nodes, byFoothold, adjacency);
        addFallEdges(tree, nodes, byFoothold, adjacency);
        addJumpEdges(nodes, byFoothold, platforms, adjacency);
        addClimbEdges(ladders, nodes, byFoothold, adjacency);
        return new AgentNavGraph(List.copyOf(nodes), adjacency);
    }

    private static int addNode(List<Node> nodes, int x, Foothold fh) {
        int id = nodes.size();
        nodes.add(new Node(x, fh.calculateFooting(x), fh));
        return id;
    }

    // -----------------------------------------------------------------------------------------
    // Edge construction
    // -----------------------------------------------------------------------------------------

    /** Walk edges: consecutive samples per foothold + welds between contiguous equal surfaces. */
    private static void addWalkEdges(List<Node> nodes, Map<Foothold, List<Integer>> byFoothold,
            List<List<Edge>> adjacency) {
        for (List<Integer> ids : byFoothold.values()) {
            for (int i = 1; i < ids.size(); i++) {
                int a = ids.get(i - 1);
                int b = ids.get(i);
                double cost = Math.abs(nodes.get(b).x() - nodes.get(a).x())
                        / AgentPhysics.WALK_SPEED_PX_PER_S;
                adjacency.get(a).add(new Edge(b, cost,
                        new AgentPathing.Hop(AgentPathing.HopType.WALK, b,
                                nodes.get(a).x(), nodes.get(b).x(), nodes.get(b).y(), null, false)));
                adjacency.get(b).add(new Edge(a, cost,
                        new AgentPathing.Hop(AgentPathing.HopType.WALK, a,
                                nodes.get(b).x(), nodes.get(a).x(), nodes.get(a).y(), null, false)));
            }
        }
        // Weld footholds that share a boundary or overlap at (near-)equal height, so walking
        // crosses segment chains (and duplicated platforms) without a jump.
        List<Foothold> footholds = new ArrayList<>(byFoothold.keySet());
        for (int i = 0; i < footholds.size(); i++) {
            for (int j = i + 1; j < footholds.size(); j++) {
                Foothold a = footholds.get(i);
                Foothold b = footholds.get(j);
                int aMin = Math.min(a.getX1(), a.getX2());
                int aMax = Math.max(a.getX1(), a.getX2());
                int bMin = Math.min(b.getX1(), b.getX2());
                int bMax = Math.max(b.getX1(), b.getX2());
                int ovMin = Math.max(aMin, bMin);
                int ovMax = Math.min(aMax, bMax);
                if (ovMin > ovMax) {
                    continue; // disjoint in x: a jump/fall edge, never a walk
                }
                // Check both overlap ends: equal-height surfaces weld, crossing slopes do not.
                for (int x : new int[]{ovMin, ovMax}) {
                    int ya = a.calculateFooting(x);
                    int yb = b.calculateFooting(x);
                    if (Math.abs(ya - yb) <= WELD_TOLERANCE_PX) {
                        weld(nodes, byFoothold, adjacency, a, b, x);
                        break;
                    }
                }
            }
        }
    }

    /** Connects the nearest samples of {@code a} and {@code b} around column {@code x}. */
    private static void weld(List<Node> nodes, Map<Foothold, List<Integer>> byFoothold,
            List<List<Edge>> adjacency, Foothold a, Foothold b, int x) {
        int na = nearestNodeId(nodes, byFoothold.get(a), x);
        int nb = nearestNodeId(nodes, byFoothold.get(b), x);
        if (na < 0 || nb < 0 || na == nb) {
            return;
        }
        double cost = Math.abs(nodes.get(nb).x() - nodes.get(na).x())
                / AgentPhysics.WALK_SPEED_PX_PER_S;
        adjacency.get(na).add(new Edge(nb, cost,
                new AgentPathing.Hop(AgentPathing.HopType.WALK, nb, nodes.get(na).x(),
                        nodes.get(nb).x(), nodes.get(nb).y(), null, false)));
        adjacency.get(nb).add(new Edge(na, cost,
                new AgentPathing.Hop(AgentPathing.HopType.WALK, na, nodes.get(nb).x(),
                        nodes.get(na).x(), nodes.get(na).y(), null, false)));
    }

    /** Fall edges: straight down from each sample onto the next surface below it. */
    private static void addFallEdges(FootholdTree tree, List<Node> nodes,
            Map<Foothold, List<Integer>> byFoothold, List<List<Edge>> adjacency) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            Foothold below = tree.findBelow(new Point(node.x(), node.y() + 1));
            if (below == null || below.isWall() || below == node.fh()) {
                continue;
            }
            List<Integer> candidates = byFoothold.get(below);
            if (candidates == null) {
                continue;
            }
            int landing = nearestNodeId(nodes, candidates, node.x());
            if (landing < 0) {
                continue;
            }
            Node dest = nodes.get(landing);
            int drop = dest.y() - node.y();
            if (drop <= 0 || drop > MAX_DROP_PX) {
                continue;
            }
            adjacency.get(i).add(new Edge(landing, AgentPhysics.fallTime(drop),
                    new AgentPathing.Hop(AgentPathing.HopType.FALL, landing, node.x(),
                            dest.x(), dest.y(), null, false)));
        }
    }

    /**
     * Jump edges, validated by real v83 arc simulation. For each ordered foothold pair at most a
     * couple of candidate arcs are simulated (takeoff at the overlap/edge sample that best
     * reaches the other platform), so the O(F²) pair loop stays cheap; arcs that would clip an
     * intervening platform are rejected inside {@link AgentPhysics#simulateJump}.
     */
    private static void addJumpEdges(List<Node> nodes, Map<Foothold, List<Integer>> byFoothold,
            List<Foothold> platforms, List<List<Edge>> adjacency) {
        double maxGap = AgentPhysics.jumpRangeForDrop(AgentPhysics.MAX_JUMP_DROP_PX);
        for (Foothold a : platforms) {
            List<Integer> aIds = byFoothold.get(a);
            if (aIds == null) {
                continue;
            }
            for (Foothold b : platforms) {
                if (b == a) {
                    continue;
                }
                int aMin = Math.min(a.getX1(), a.getX2());
                int aMax = Math.max(a.getX1(), a.getX2());
                int bMin = Math.min(b.getX1(), b.getX2());
                int bMax = Math.max(b.getX1(), b.getX2());
                int ovMin = Math.max(aMin, bMin);
                int ovMax = Math.min(aMax, bMax);
                if (ovMin <= ovMax) {
                    // Overlapping: only higher targets need a jump (lower ones are fall edges).
                    for (int x : new int[]{ovMin, ovMax}) {
                        int dy = b.calculateFooting(x) - a.calculateFooting(x);
                        if (dy >= 0 || dy < -AgentPhysics.JUMP_APEX_PX) {
                            continue;
                        }
                        int takeoff = nearestNodeId(nodes, aIds, x);
                        if (tryJumpEdge(nodes, byFoothold, platforms, adjacency, takeoff, b, 0)) {
                            break; // one validated arc per pair is enough; walking reaches it
                        }
                    }
                } else {
                    int gap = Math.max(bMin - aMax, aMin - bMax);
                    if (gap > maxGap) {
                        continue; // physically unjumpable gap
                    }
                    boolean bIsRight = bMin > aMax;
                    int edgeX = bIsRight ? aMax : aMin;
                    int direction = bIsRight ? 1 : -1;
                    int takeoff = nearestNodeId(nodes, aIds, edgeX);
                    tryJumpEdge(nodes, byFoothold, platforms, adjacency, takeoff, b, direction);
                }
            }
        }
    }

    /** Simulates an arc from {@code takeoffNodeId} towards {@code target}; adds the edge if it lands. */
    private static boolean tryJumpEdge(List<Node> nodes, Map<Foothold, List<Integer>> byFoothold,
            List<Foothold> allFootholds, List<List<Edge>> adjacency, int takeoffNodeId,
            Foothold target, int direction) {
        if (takeoffNodeId < 0) {
            return false;
        }
        Node takeoff = nodes.get(takeoffNodeId);
        AgentPhysics.Landing landing = AgentPhysics.simulateJump(
                new Point(takeoff.x(), takeoff.y()), direction, target, takeoff.fh(), allFootholds);
        if (landing == null) {
            return false;
        }
        List<Integer> landingIds = byFoothold.get(target);
        if (landingIds == null) {
            return false;
        }
        int landingNode = nearestNodeId(nodes, landingIds, landing.x());
        if (landingNode < 0 || landingNode == takeoffNodeId) {
            return false;
        }
        // Cost: airtime plus the walk from the landing point to the continuation sample.
        double cost = landing.airtime()
                + Math.abs(nodes.get(landingNode).x() - landing.x())
                / AgentPhysics.WALK_SPEED_PX_PER_S;
        boolean up = landing.y() < takeoff.y();
        adjacency.get(takeoffNodeId).add(new Edge(landingNode, cost,
                new AgentPathing.Hop(AgentPathing.HopType.JUMP, landingNode, takeoff.x(),
                        landing.x(), landing.y(), null, up)));
        return true;
    }

    /** Climb edges: grabbable foothold → dismountable foothold via each usable rope. */
    private static void addClimbEdges(List<AgentPathing.Ladder> ladders, List<Node> nodes,
            Map<Foothold, List<Integer>> byFoothold, List<List<Edge>> adjacency) {
        for (AgentPathing.Ladder ladder : ladders) {
            if (!ladder.usable()) {
                continue;
            }
            for (Map.Entry<Foothold, List<Integer>> grabEntry : byFoothold.entrySet()) {
                if (!AgentPathing.canGrab(grabEntry.getKey(), ladder)) {
                    continue;
                }
                for (Map.Entry<Foothold, List<Integer>> mountEntry : byFoothold.entrySet()) {
                    Foothold grabFh = grabEntry.getKey();
                    Foothold mountFh = mountEntry.getKey();
                    if (grabFh == mountFh
                            || !AgentPathing.canDismount(mountFh, ladder)) {
                        continue;
                    }
                    boolean up = mountFh.calculateFooting(ladder.x()) < grabFh.calculateFooting(ladder.x());
                    int from = nearestNodeId(nodes, grabEntry.getValue(), ladder.x());
                    int to = nearestNodeId(nodes, mountEntry.getValue(), ladder.x());
                    if (from < 0 || to < 0) {
                        continue;
                    }
                    double cost = Math.abs(ladder.top() - ladder.bottom()) / CLIMB_SPEED_PX_PER_S;
                    // The climb manoeuvre walks to the rope column, climbs along the real rope and
                    // dismounts onto the far surface (AgentPathing.executeHop).
                    adjacency.get(from).add(new Edge(to, cost,
                            new AgentPathing.Hop(AgentPathing.HopType.CLIMB, to, ladder.x(),
                                    ladder.x(), mountFh.calculateFooting(ladder.x()), ladder, up)));
                }
            }
        }
    }

    /** Nearest sample id on {@code candidates} to column {@code x}, or -1. */
    private static int nearestNodeId(List<Node> nodes, List<Integer> candidates, int x) {
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int id : candidates) {
            int dist = Math.abs(nodes.get(id).x() - x);
            if (dist < bestDist) {
                best = id;
                bestDist = dist;
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------------------------
    // A* search
    // -----------------------------------------------------------------------------------------

    /**
     * A* over the cached graph, time-costed (walk/fall/jump/climb speeds), with an admissible
     * straight-line/600px-per-s heuristic (no agent manoeuvre is faster than the fall speed).
     *
     * @return the manoeuvre list from the start towards the goal, or {@code null} when no route
     *         exists; an empty list means "already there"
     */
    List<AgentPathing.Hop> findRoute(Point start, Foothold startFh, Point goal) {
        if (nodes.isEmpty()) {
            return null;
        }
        int from = startNodeId(start, startFh);
        int to = goalNodeId(goal, startFh);
        if (from < 0 || to < 0) {
            return null;
        }
        if (from == to) {
            return List.of();
        }
        int n = nodes.size();
        double[] dist = new double[n];
        int[] prevNode = new int[n];
        int[] prevEdge = new int[n];
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(prevNode, -1);
        java.util.Arrays.fill(prevEdge, -1);
        dist[from] = 0;
        // int-encoded priority queue entries: (dist, node)
        java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>(
                (a, b) -> Double.compare(a[0], b[0]));
        queue.add(new long[]{Double.doubleToLongBits(0), from});
        while (!queue.isEmpty()) {
            long[] top = queue.poll();
            int node = (int) top[1];
            double d = Double.longBitsToDouble(top[0]);
            if (d > dist[node]) {
                continue;
            }
            if (node == to) {
                break;
            }
            List<Edge> edges = adjacency.get(node);
            for (int e = 0; e < edges.size(); e++) {
                Edge edge = edges.get(e);
                double nd = d + edge.cost();
                if (nd < dist[edge.to()]) {
                    dist[edge.to()] = nd;
                    prevNode[edge.to()] = node;
                    prevEdge[edge.to()] = e;
                    queue.add(new long[]{Double.doubleToLongBits(nd), edge.to()});
                }
            }
        }
        if (prevNode[to] == -1) {
            return null;
        }
        List<AgentPathing.Hop> route = new ArrayList<>();
        for (int node = to; node != from; node = prevNode[node]) {
            route.add(adjacency.get(prevNode[node]).get(prevEdge[node]).hop());
        }
        java.util.Collections.reverse(route);
        return route;
    }

    /**
     * The node the route starts from: the closest sample, strongly preferring the agent's own
     * foothold (so the first manoeuvre is a walk, not an improvised jump).
     */
    private int startNodeId(Point start, Foothold startFh) {
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            long dx = node.x() - start.x;
            long dy = node.y() - start.y;
            long score = dx * dx + dy * dy + (node.fh() == startFh ? 0 : 250_000L);
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The node the route aims at: on the foothold under {@code goal.x} whose surface is closest
     * to {@code goal.y} (mid-air goals are never chased — mirrors the legacy goal selection).
     * When two surfaces tie (a mid-air goal exactly between levels, e.g. 103000802's
     * (1100,-75) between the -135 cloud and the quirk-resolved -15 connector), the surface the
     * agent already stands on wins: re-routing to an equidistant other level while the direct
     * step reads the same target as "lower platform" is what made the agent ping-pong between
     * the levels (§4.20).
     */
    private int goalNodeId(Point goal, Foothold startFh) {
        Foothold bestFh = null;
        int bestDist = Integer.MAX_VALUE;
        for (Node node : nodes) {
            if (goal.x < Math.min(node.fh().getX1(), node.fh().getX2())
                    || goal.x > Math.max(node.fh().getX1(), node.fh().getX2())) {
                continue;
            }
            int dist = Math.abs(node.fh().calculateFooting(goal.x) - goal.y);
            if (dist < bestDist || (dist == bestDist && node.fh() == startFh)) {
                bestDist = dist;
                bestFh = node.fh();
            }
        }
        if (bestFh == null) {
            return -1;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).fh() == bestFh) {
                candidates.add(i);
            }
        }
        return nearestNodeId(nodes, candidates, goal.x);
    }
}
