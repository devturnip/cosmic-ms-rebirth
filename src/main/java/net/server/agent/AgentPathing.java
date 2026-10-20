package net.server.agent;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foothold/rope pathfinding for agent companions.
 *
 * <p>When {@link AgentMovement#moveToSmart}'s direct ground-aware step stalls (platform edge,
 * gap, or a target on a strictly higher platform) it hands the tick to
 * {@link #routeStep}, which executes <em>one clamped step</em> of a route per behaviour tick.
 * Routes come from a per-map <b>cached navigation graph</b> ({@link AgentNavGraph}: stand points
 * sampled along the footholds, walk/fall/jump/climb edges, A*), with jump arcs validated against
 * real v83 physics ({@link AgentPhysics}); while a graph is warming — and for rope starts — a
 * legacy bounded BFS over the footholds + ropes takes over. Edge types:
 * <ul>
 *   <li><b>walk</b> — along a foothold or across welded equal-height segments;</li>
 *   <li><b>fall</b> — only ever downward, onto a real surface below;</li>
 *   <li><b>jump</b> — a simulated v83 arc (gravity 2000 px/s², jump velocity 555 px/s) that lands
 *       on a real foothold and clips no intervening platform;</li>
 *   <li><b>climb</b> — a usable {@code ladderRope} entry from the map's WZ image whose bottom end
 *       is grabbable from a foothold ({@link #canGrab}) and whose other end can be dismounted onto
 *       a foothold ({@link #canDismount}).</li>
 * </ul>
 *
 * <p>Safety invariants (see hand-off §4.6/§4.19/§4.20): every emitted step is still clamped per
 * axis by {@code AgentMovement.MAX_STEP} and to the map area; a jump is a single scripted arc
 * that lands on a real foothold; a climb only ever happens along a real WZ rope/ladder (the climb
 * stance 16); agents never teleport and never change maps here. Routes are cached per session
 * ({@link AgentMovementState}) so a multi-tick manoeuvre is not re-picked every tick, and are
 * invalidated on a new target, a map change, or a stall.
 *
 * <p><b>Constants.</b> Derived from the server's own WZ physics ({@code Physics.img.xml}):
 * a v83 jump lifts the character {@code v0²/2g ≈ 77px} (one platform of the common 60–90px WZ
 * spacing) and covers {@code walkSpeed · 2v0/g ≈ 69px} of flat horizontal distance; drops extend
 * the reach (see {@link AgentPhysics#jumpRangeForDrop}). Ropes are grabbable while their end hangs
 * within about a character height above the surface (70px) and dismountable while the target
 * surface sits within a jump height of the rope's far end.
 *
 * <p><b>Threading.</b> {@code ladderRope} data is loaded lazily per map from Map.wz and cached in
 * a static map. A cold map is warmed on a daemon thread and {@link #ladders} returns an empty
 * list for that tick, so the per-session action executor never blocks on a WZ parse (ropes simply
 * become available from the next tick on).
 */
public final class AgentPathing {
    private static final Logger log = LoggerFactory.getLogger(AgentPathing.class);

    /** Max height (px) a jump can mount — the v83 jump apex (77px, from Physics.img.xml). */
    static final int JUMP_HEIGHT_PX = (int) AgentPhysics.JUMP_APEX_PX;
    /** Max horizontal gap (px) a flat jump clears — one airtime at walk speed (~69px). */
    static final int JUMP_GAP_PX = (int) AgentPhysics.JUMP_FLAT_RANGE_PX;
    /** Max height gained during a gap jump (a running jump may also rise a little). */
    static final int JUMP_LEVEL_TOLERANCE = 30;
    /** Max height lost during a gap jump; beyond that the agent simply falls instead. */
    static final int JUMP_DROP_LIMIT = AgentMovement.MAX_STEP;
    /** Horizontal reach (px) when grabbing a rope from a foothold or aligning a jump. */
    static final int CLIMB_REACH_PX = 25;
    /** How far above a surface a rope end may hang and still be grabbable (~character height). */
    static final int CLIMB_ATTACH_PX = 70;
    /** How far below a surface a rope end may end and still be grabbable. */
    static final int CLIMB_ATTACH_BELOW_PX = 10;
    /** Largest drop modelled as a fall edge (anything longer is not worth routing). */
    static final int MAX_DROP_PX = 2000;
    /** Maps with more standable footholds than this are not graph-searched (safety bound). */
    static final int MAX_PATH_FOOTHOLDS = 256;

    private AgentPathing() {
    }

    /**
     * One {@code ladderRope} entry from a Map.wz image. {@code l}: 0 = rope, 1 = ladder;
     * {@code uf}: usable flag. {@code y1}/{@code y2} are the WZ ends (their order varies);
     * use {@link #top()}/{@link #bottom()} — y grows downward in MapleStory.
     */
    public record Ladder(int x, int y1, int y2, boolean ladder, boolean usable, int page) {
        public int top() {
            return Math.min(y1, y2);
        }

        public int bottom() {
            return Math.max(y1, y2);
        }
    }

    /** One manoeuvre of a route. {@code viaX} is the takeoff/grab column; dest the landing point. */
    record Hop(HopType type, int to, int viaX, int destX, int destY, Ladder ladder, boolean up) {
    }

    enum HopType {
        WALK, FALL, JUMP, CLIMB
    }

    private static final Map<Integer, List<Ladder>> LADDER_CACHE = new ConcurrentHashMap<>();
    private static final Set<Integer> WARMING = ConcurrentHashMap.newKeySet();

    /**
     * The usable ropes/ladders of the agent's map, loaded lazily from Map.wz (cached per map id).
     * A cold map is warmed on a daemon thread and an empty list is returned for that tick, so the
     * action-executor thread never blocks on a WZ parse.
     */
    public static List<Ladder> ladders(MapleMap map) {
        int mapId = map == null ? 0 : map.getId();
        List<Ladder> cached = LADDER_CACHE.get(mapId);
        if (cached != null) {
            return cached;
        }
        warmAsync(mapId);
        return List.of();
    }

    /** Seeds/overrides the ladder cache (used by tests; never blocks). */
    static void putLadders(int mapId, List<Ladder> ladders) {
        LADDER_CACHE.put(mapId, List.copyOf(ladders));
    }

    /**
     * Synchronous ladder load for nav-graph warm builds (runs on the daemon warm thread, never
     * on a tick thread). Shares the ladder cache with {@link #ladders(MapleMap)}.
     */
    static List<Ladder> laddersForGraph(int mapId) {
        return LADDER_CACHE.computeIfAbsent(mapId, AgentPathing::loadLadders);
    }

    private static void warmAsync(int mapId) {
        if (!WARMING.add(mapId)) {
            return; // a warm load is already in flight for this map
        }
        final Thread loader = new Thread(() -> {
            try {
                LADDER_CACHE.computeIfAbsent(mapId, AgentPathing::loadLadders);
            } finally {
                WARMING.remove(mapId);
            }
        }, "agent-ladder-warm-" + mapId);
        loader.setDaemon(true);
        loader.start();
    }

    /** Parses {@code ladderRope} from Map.wz, mirroring MapFactory's map-name/link resolution. */
    private static List<Ladder> loadLadders(int mapId) {
        try {
            DataProvider wz = DataProviderFactory.getDataProvider(WZFiles.MAP);
            Data mapData = wz.getData(mapName(mapId));
            if (mapData == null) {
                return List.of();
            }
            Data info = mapData.getChildByPath("info");
            if (info != null) {
                String link = DataTool.getString(info.getChildByPath("link"), "");
                if (!link.isEmpty()) {
                    Data linked = wz.getData(mapName(Integer.parseInt(link)));
                    if (linked != null) {
                        mapData = linked;
                    }
                }
            }
            Data root = mapData.getChildByPath("ladderRope");
            if (root == null) {
                return List.of();
            }
            List<Ladder> ladders = new ArrayList<>();
            for (Data entry : root) {
                ladders.add(new Ladder(
                        DataTool.getInt(entry.getChildByPath("x"), 0),
                        DataTool.getInt(entry.getChildByPath("y1"), 0),
                        DataTool.getInt(entry.getChildByPath("y2"), 0),
                        DataTool.getInt(entry.getChildByPath("l"), 0) == 1,
                        DataTool.getInt(entry.getChildByPath("uf"), 0) == 1,
                        DataTool.getInt(entry.getChildByPath("page"), 0)));
            }
            return List.copyOf(ladders);
        } catch (Throwable t) {
            // No usable WZ tree: agents simply never climb on this map.
            log.debug("Could not load ladderRope for map {}: {}", mapId, t.toString());
            return List.of();
        }
    }

    /** Same node path as {@code MapFactory.getMapName}. */
    private static String mapName(int mapId) {
        return String.format("Map/Map%d/%09d.img", mapId / 100000000, mapId);
    }

    // -----------------------------------------------------------------------------------------
    // Per-tick routing
    // -----------------------------------------------------------------------------------------

    /**
     * Executes one clamped step of a route toward {@code target}. Called by
     * {@code AgentMovement.moveToSmart} when its direct step stalled. With a per-session
     * {@link AgentMovementState}, the stored route is <b>continued</b> (walk-to-takeoff → arc →
     * landing → …) instead of being re-picked every tick; it is invalidated by a moved target, a
     * map change, or a stall, after which a fresh route is computed — from the cached
     * {@link AgentNavGraph} when one is available (grounded agents), else the legacy bounded BFS
     * (also the rope-start path). If no route exists the agent simply stays put.
     *
     * @return whether a route step was executed ({@code false} = no route, the agent holds)
     */
    static boolean routeStep(AgentSession session, Point target, int maxStep) {
        Character chr = session.getPlayer();
        if (chr == null || target == null) {
            return false;
        }
        MapleMap map = chr.getMap();
        FootholdTree tree = map == null ? null : map.getFootholds();
        Point current = chr.getPosition();
        if (tree == null || current == null) {
            return false;
        }
        List<Ladder> ladders = ladders(map);
        List<Foothold> footholds = standableFootholds(tree);
        if (footholds.isEmpty()) {
            return false;
        }
        // Climbing state is tracked explicitly (AgentSession#isClimbingRope, set by the last
        // emitted step) because position alone is ambiguous: a rope column often overlaps
        // platform surfaces, which misclassified landed agents as mid-climb (froze them at the
        // rope top) and mid-climb agents as grounded (dropped them off the rope).
        boolean hanging = session.isClimbingRope() && ladderAt(ladders, current) != null;
        Ladder onLadder = hanging ? ladderAt(ladders, current) : null;
        Foothold ground = hanging ? null : AgentMovement.footholdAt(tree, current);
        if (!hanging && ground == null) {
            // Position fallback (e.g. no step emitted yet): on a rope column without support.
            onLadder = ladderAt(ladders, current);
        }
        if (onLadder == null && ground == null) {
            return false; // unsupported: the direct step holds position
        }

        AgentMovementState state = session.movementState();
        if (state != null) {
            int mapId = map.getId();
            // Invalidates on a new target / map change (follow warp, portal, respawn).
            state.validate(mapId, target);
            if (state.hasRoute()) {
                if (state.stalled(current)) {
                    state.clear(); // wedged: re-route from scratch on the next stall
                } else {
                    return executeRouteHop(session, tree, state, current, ground, maxStep);
                }
            }
            List<Hop> route = computeRoute(tree, mapId, ladders, footholds, current, ground,
                    onLadder, target);
            if (route.isEmpty()) {
                return false;
            }
            route = compressWalks(route);
            state.start(route, target, mapId);
            return executeRouteHop(session, tree, state, current, ground, maxStep);
        }

        // Stateless (mocked test sessions): the legacy one-step-per-tick behaviour.
        List<Hop> route = computeRoute(tree, map.getId(), ladders, footholds, current, ground,
                onLadder, target);
        if (route.isEmpty()) {
            return false;
        }
        executeHop(session, tree, compressWalks(route).get(0), current, ground, maxStep);
        return true;
    }

    /**
     * Merges runs of consecutive walk manoeuvres (one per ~15px nav-graph sample) into single
     * walks, so a tick advances up to {@code MAX_STEP} instead of one sample.
     */
    private static List<Hop> compressWalks(List<Hop> route) {
        if (route.size() < 2) {
            return route;
        }
        List<Hop> out = new ArrayList<>(route.size());
        int i = 0;
        while (i < route.size()) {
            Hop hop = route.get(i);
            if (hop.type() != HopType.WALK) {
                out.add(hop);
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < route.size() && route.get(j + 1).type() == HopType.WALK) {
                j++;
            }
            Hop last = route.get(j);
            out.add(new Hop(HopType.WALK, last.to(), hop.viaX(), last.destX(), last.destY(),
                    null, false));
            i = j + 1;
        }
        return out;
    }

    /**
     * Route source: the cached nav graph for grounded agents (real map ids only — mocked test
     * maps report id 0), else/otherwise the legacy bounded BFS, which is also the rope-start
     * path (graphs carry no rope nodes) and the fallback while a graph is warming or declined.
     */
    private static List<Hop> computeRoute(FootholdTree tree, int mapId, List<Ladder> ladders,
            List<Foothold> footholds, Point current, Foothold ground, Ladder onLadder,
            Point target) {
        if (onLadder == null && mapId > 0) {
            AgentNavGraph graph = AgentNavGraph.forMap(mapId, tree);
            if (graph != null && !graph.declined()) {
                List<Hop> hops = graph.findRoute(current, ground, target);
                if (hops != null) {
                    return hops;
                }
            }
        }
        if (footholds.size() > MAX_PATH_FOOTHOLDS) {
            return List.of();
        }
        return findRoute(footholds, ladders, current, ground, onLadder, target);
    }

    /** Executes the state's current manoeuvre, skipping/advancing completed ones. */
    private static boolean executeRouteHop(AgentSession session, FootholdTree tree,
            AgentMovementState state, Point current, Foothold ground, int maxStep) {
        while (state.hasRoute() && hopComplete(state.currentHop(), current)) {
            state.advance();
        }
        if (!state.hasRoute()) {
            state.clear();
            return false; // route exhausted without a move (agent already at the goal)
        }
        Hop hop = state.currentHop();
        executeHop(session, tree, hop, current, ground, maxStep);
        if (hopComplete(hop, current)) {
            state.advance();
        }
        return true;
    }

    /** Whether the agent has arrived at {@code hop}'s destination (manoeuvre complete). */
    private static boolean hopComplete(Hop hop, Point current) {
        // Walks are x-complete (the y follows the surface); arcs/falls/climbs must also land.
        int yTol = hop.type() == HopType.WALK ? Integer.MAX_VALUE : 3;
        return Math.abs(current.x - hop.destX()) <= 2
                && Math.abs(current.y - hop.destY()) <= yTol;
    }

    /**
     * Breadth-first search (fewest manoeuvres) over footholds + rope endpoints. Fully bounded:
     * the node set is capped at {@link #MAX_PATH_FOOTHOLDS} footholds plus the map's ropes and
     * BFS visits each node at most once. Returns the manoeuvre list, or empty when unreachable.
     */
    static List<Hop> findRoute(List<Foothold> footholds, List<Ladder> ladders, Point current,
            Foothold ground, Ladder onLadder, Point target) {
        int n = footholds.size();
        int goal = goalFoothold(footholds, target);
        if (goal < 0) {
            return List.of(); // no platform under the target: never chase positions mid-air
        }
        int start;
        if (onLadder != null) {
            int idx = ladders.indexOf(onLadder);
            if (idx < 0) {
                return List.of();
            }
            start = n + idx;
        } else {
            start = footholds.indexOf(ground);
            if (start < 0) {
                return List.of();
            }
            if (start == goal) {
                return List.of(); // already on the target platform: nothing to route
            }
        }
        int total = n + ladders.size();
        List<List<Hop>> edges = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            edges.add(new ArrayList<>());
        }
        addPlatformEdges(footholds, current, edges);
        addLadderEdges(footholds, ladders, n, edges);

        int[] parent = new int[total];
        Hop[] via = new Hop[total];
        Arrays.fill(parent, -1);
        parent[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        int expansions = 0;
        while (!queue.isEmpty() && expansions++ <= total) {
            int node = queue.poll();
            if (node == goal) {
                break;
            }
            for (Hop hop : edges.get(node)) {
                if (parent[hop.to()] == -1) {
                    parent[hop.to()] = node;
                    via[hop.to()] = hop;
                    queue.add(hop.to());
                }
            }
        }
        if (parent[goal] == -1) {
            return List.of();
        }
        List<Hop> route = new ArrayList<>();
        for (int node = goal; node != start; node = parent[node]) {
            route.add(via[node]);
        }
        Collections.reverse(route);
        return route;
    }

    /** Fall and jump edges between every pair of platforms (cheap geometric tests only). */
    private static void addPlatformEdges(List<Foothold> footholds, Point current,
            List<List<Hop>> edges) {
        int n = footholds.size();
        int[] mins = new int[n];
        int[] maxs = new int[n];
        for (int i = 0; i < n; i++) {
            Foothold fh = footholds.get(i);
            mins[i] = Math.min(fh.getX1(), fh.getX2());
            maxs[i] = Math.max(fh.getX1(), fh.getX2());
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                int aMin = mins[i];
                int aMax = maxs[i];
                int bMin = mins[j];
                int bMax = maxs[j];
                int ovMin = Math.max(aMin, bMin);
                int ovMax = Math.min(aMax, bMax);
                Foothold a = footholds.get(i);
                Foothold b = footholds.get(j);
                if (ovMin <= ovMax) {
                    // Take off from the overlap column closest to the agent (stateless: the route
                    // is rebuilt each tick, so viaX converges on the agent's position).
                    int viaX = Math.max(ovMin, Math.min(ovMax, current.x));
                    int dy = b.calculateFooting(viaX) - a.calculateFooting(viaX);
                    if (dy > 0 && dy <= MAX_DROP_PX) {
                        // Fall: only ever downward, straight onto the surface below.
                        edges.get(i).add(new Hop(HopType.FALL, j, viaX, viaX,
                                b.calculateFooting(viaX), null, false));
                    } else if (dy < 0 && dy >= -JUMP_HEIGHT_PX) {
                        // Jump straight up onto an overlapping higher platform.
                        edges.get(i).add(new Hop(HopType.JUMP, j, viaX, viaX,
                                b.calculateFooting(viaX), null, true));
                    }
                } else {
                    int gap = Math.max(bMin - aMax, aMin - bMax); // > 0 when disjoint
                    if (gap > 0 && gap <= JUMP_GAP_PX) {
                        boolean bIsRight = bMin > aMax;
                        int viaX = bIsRight ? aMax : aMin;
                        int destX = bIsRight ? bMin : bMax;
                        int dy = b.calculateFooting(destX) - a.calculateFooting(viaX);
                        if (dy >= -JUMP_LEVEL_TOLERANCE && dy <= JUMP_DROP_LIMIT) {
                            edges.get(i).add(new Hop(HopType.JUMP, j, viaX, destX,
                                    b.calculateFooting(destX), null, dy < 0));
                        }
                    }
                }
            }
        }
    }

    /** Climb edges via each usable rope, plus continuation edges from the rope itself. */
    private static void addLadderEdges(List<Foothold> footholds, List<Ladder> ladders, int n,
            List<List<Hop>> edges) {
        for (int li = 0; li < ladders.size(); li++) {
            Ladder ladder = ladders.get(li);
            if (!ladder.usable()) {
                continue;
            }
            List<Integer> grabbable = new ArrayList<>();
            List<Integer> dismountable = new ArrayList<>();
            for (int f = 0; f < n; f++) {
                Foothold fh = footholds.get(f);
                if (canGrab(fh, ladder)) {
                    grabbable.add(f);
                }
                if (canDismount(fh, ladder)) {
                    dismountable.add(f);
                }
            }
            for (int a : grabbable) {
                for (int b : dismountable) {
                    if (a == b) {
                        continue;
                    }
                    // a (bottom) -> b (top): climb up and dismount onto b.
                    edges.get(a).add(new Hop(HopType.CLIMB, b, ladder.x(), ladder.x(),
                            footholds.get(b).calculateFooting(ladder.x()), ladder, true));
                    // b (top) -> a (bottom): climb down and dismount onto a.
                    edges.get(b).add(new Hop(HopType.CLIMB, a, ladder.x(), ladder.x(),
                            footholds.get(a).calculateFooting(ladder.x()), ladder, false));
                }
            }
            // A rope the agent is already hanging on continues to either end.
            int ropeNode = n + li;
            for (int b : dismountable) {
                edges.get(ropeNode).add(new Hop(HopType.CLIMB, b, ladder.x(), ladder.x(),
                        footholds.get(b).calculateFooting(ladder.x()), ladder, true));
            }
            for (int a : grabbable) {
                if (!dismountable.contains(a)) {
                    edges.get(ropeNode).add(new Hop(HopType.CLIMB, a, ladder.x(), ladder.x(),
                            footholds.get(a).calculateFooting(ladder.x()), ladder, false));
                }
            }
        }
    }

    /** Whether an agent standing on {@code fh} can grab {@code ladder}'s bottom end. */
    static boolean canGrab(Foothold fh, Ladder ladder) {
        int minX = Math.min(fh.getX1(), fh.getX2());
        int maxX = Math.max(fh.getX1(), fh.getX2());
        if (ladder.x() < minX - CLIMB_REACH_PX || ladder.x() > maxX + CLIMB_REACH_PX) {
            return false;
        }
        int surface = fh.calculateFooting(ladder.x());
        return ladder.bottom() <= surface + CLIMB_ATTACH_BELOW_PX
                && ladder.bottom() >= surface - CLIMB_ATTACH_PX;
    }

    /** Whether {@code ladder}'s far end can be left onto {@code fh} (surface within a jump). */
    static boolean canDismount(Foothold fh, Ladder ladder) {
        int minX = Math.min(fh.getX1(), fh.getX2());
        int maxX = Math.max(fh.getX1(), fh.getX2());
        if (ladder.x() < minX - CLIMB_REACH_PX || ladder.x() > maxX + CLIMB_REACH_PX) {
            return false;
        }
        int surface = fh.calculateFooting(ladder.x());
        return surface >= ladder.top() - JUMP_HEIGHT_PX
                && surface <= ladder.top() + CLIMB_ATTACH_BELOW_PX;
    }

    /** The rope/ladder the agent is currently hanging on (x aligned, y within the span). */
    static Ladder ladderAt(List<Ladder> ladders, Point current) {
        for (Ladder ladder : ladders) {
            if (Math.abs(current.x - ladder.x()) <= CLIMB_REACH_PX
                    && current.y >= ladder.top() - CLIMB_REACH_PX
                    && current.y <= ladder.bottom() + CLIMB_REACH_PX) {
                return ladder;
            }
        }
        return null;
    }

    /**
     * Whether the agent is mid-climb on a real rope: its session's last step was a climb and its
     * position sits on a usable rope's column and span. The direct step would otherwise read that
     * position as unsupported (or worse, grounded) and interrupt the climb.
     */
    static boolean hangingOnLadder(AgentSession session) {
        Character chr = session == null ? null : session.getPlayer();
        MapleMap map = chr == null ? null : chr.getMap();
        Point current = chr == null ? null : chr.getPosition();
        FootholdTree tree = map == null ? null : map.getFootholds();
        if (tree == null || current == null || !session.isClimbingRope()) {
            return false;
        }
        return ladderAt(ladders(map), current) != null;
    }

    /** The foothold under {@code target.x} whose surface is closest to {@code target.y}. */
    private static int goalFoothold(List<Foothold> footholds, Point target) {
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < footholds.size(); i++) {
            Foothold fh = footholds.get(i);
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (target.x < minX || target.x > maxX) {
                continue;
            }
            int dist = Math.abs(fh.calculateFooting(target.x) - target.y);
            if (dist < bestDist) {
                best = i;
                bestDist = dist;
            }
        }
        return best;
    }

    private static List<Foothold> standableFootholds(FootholdTree tree) {
        List<Foothold> footholds = new ArrayList<>();
        for (Foothold fh : tree.getAllFootholds()) {
            if (!fh.isWall()) {
                footholds.add(fh);
            }
        }
        return footholds;
    }

    // -----------------------------------------------------------------------------------------
    // Hop execution (one clamped step per tick)
    // -----------------------------------------------------------------------------------------

    static void executeHop(AgentSession session, FootholdTree tree, Hop hop, Point current,
            Foothold ground, int maxStep) {
        switch (hop.type()) {
            case WALK -> {
                // Walk toward the manoeuvre's destination column on whatever support actually
                // exists under the next step: the agent's own ground while it covers the way,
                // then welded adjacent segments (surface within tolerance) — never a blind step
                // clamped into the current segment's range (which made agents stall at welds).
                AgentMovement.walkOnGround(session, tree, ground, current, hop.destX(), maxStep);
            }
            case JUMP -> {
                if (ground != null && Math.abs(current.x - hop.viaX()) > CLIMB_REACH_PX) {
                    // Walk to the takeoff column first; the jump fires on a later tick.
                    moveAlongToward(session, ground, current.x, hop.viaX(), maxStep);
                    return;
                }
                // One scripted jump arc that lands on a real foothold (dest is on the edge
                // foothold by construction, and |dx|, |dy| stay within MAX_STEP). A real relative
                // jump fragment precedes the absolute step so clients animate the arc.
                AgentMovement.moveTo(session, hop.destX(), hop.destY(), maxStep,
                        hop.destX() < current.x
                                ? AgentMovement.JUMP_STANCE_LEFT : AgentMovement.JUMP_STANCE_RIGHT,
                        true);
            }
            case FALL -> {
                if (ground != null && Math.abs(current.x - hop.viaX()) > 2) {
                    moveAlongToward(session, ground, current.x, hop.viaX(), maxStep);
                    return;
                }
                // Straight down to whatever is actually below (findBelow may pick an
                // intermediate platform; the route recomputes next tick and adapts). A gravity
                // fall keeps the air/jump stance — never the climb stance.
                Foothold below = tree.findBelow(new Point(current.x, current.y + 1));
                if (below != null && !below.isWall()) {
                    AgentMovement.moveTo(session, current.x, below.calculateFooting(current.x),
                            maxStep, AgentMovement.fallStance(session.getPlayer()));
                }
            }
            case CLIMB -> {
                Ladder ladder = hop.ladder();
                int climbTo = hop.up() ? ladder.top() : ladder.bottom();
                if (ground != null && !session.isClimbingRope()
                        && Math.abs(current.x - ladder.x()) > CLIMB_REACH_PX) {
                    // Walk to the rope column (clamped to the platform edge) before grabbing.
                    int alignedX = Math.max(Math.min(ladder.x(), Math.max(ground.getX1(), ground.getX2())),
                            Math.min(ground.getX1(), ground.getX2()));
                    moveAlongToward(session, ground, current.x, alignedX, maxStep);
                    return;
                }
                boolean atEnd = hop.up() ? current.y <= climbTo : current.y >= climbTo;
                if (!atEnd) {
                    // Climb stance (16) along the real rope, capped at the rope's far end.
                    int step = Math.min(Math.max(1, maxStep), Math.abs(climbTo - current.y));
                    int newY = current.y + (hop.up() ? -step : step);
                    AgentMovement.moveTo(session, ladder.x(), newY, maxStep,
                            AgentMovement.CLIMB_STANCE);
                    return;
                }
                // Dismount onto the far platform's surface: a jump off the end when rising, a
                // controlled drop (air stance, never climb) when leaving downward.
                boolean jumpOff = hop.destY() < current.y;
                AgentMovement.moveTo(session, hop.destX(), hop.destY(), maxStep,
                        jumpOff ? AgentMovement.JUMP_STANCE_RIGHT
                                : AgentMovement.fallStance(session.getPlayer()),
                        jumpOff);
            }
        }
    }

    /** {@code AgentMovement.moveAlong} with the destination clamped into the foothold's range. */
    private static void moveAlongToward(AgentSession session, Foothold foothold, int fromX,
            int toX, int maxStep) {
        int minX = Math.min(foothold.getX1(), foothold.getX2());
        int maxX = Math.max(foothold.getX1(), foothold.getX2());
        AgentMovement.moveAlong(session, foothold, fromX, Math.max(minX, Math.min(maxX, toX)),
                maxStep);
    }
}
