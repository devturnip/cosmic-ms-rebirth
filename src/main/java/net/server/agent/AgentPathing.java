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
 * {@link #routeStep}, which searches a small graph over the map's footholds and ropes/ladders
 * and executes <em>one clamped step</em> of the route per behaviour tick. The graph edges are:
 * <ul>
 *   <li><b>fall</b> — only ever downward, onto a foothold that horizontally overlaps the takeoff
 *       platform;</li>
 *   <li><b>jump</b> — a short arc that lands on a real foothold: straight up onto an overlapping
 *       platform at most {@link #JUMP_HEIGHT_PX} higher, or across a gap of at most
 *       {@link #JUMP_GAP_PX} (gaining at most {@link #JUMP_LEVEL_TOLERANCE} height);</li>
 *   <li><b>climb</b> — a usable {@code ladderRope} entry from the map's WZ image whose bottom end
 *       is grabbable from a foothold ({@link #canGrab}) and whose other end can be dismounted onto
 *       a foothold ({@link #canDismount}).</li>
 * </ul>
 *
 * <p>Safety invariants (see hand-off §4.6/§4.19): every emitted step is still clamped per axis by
 * {@code AgentMovement.MAX_STEP} and to the map area; a jump is a single scripted arc that lands
 * on a real foothold; a climb only ever happens along a real WZ rope/ladder (the climb stance 16);
 * agents never teleport and never change maps here. The route is recomputed from the agent's
 * current position every tick, so an intercepted fall or a changed situation self-corrects and
 * no per-session route state can go stale.
 *
 * <p><b>Constants.</b> A v83 jump lifts the character roughly one platform of the common 60–90px
 * WZ spacing (~100px apex) and covers about four tiles of horizontal distance during the ~0.5s
 * airtime at walk speed (~120px). Both bounds also stay inside {@code MAX_STEP} (150px) so a jump
 * always fits a single clamped tick. Ropes are grabbable while their end hangs within about a
 * character height above the surface (70px) and dismountable while the target surface sits within
 * a jump height of the rope's far end.
 *
 * <p><b>Threading.</b> {@code ladderRope} data is loaded lazily per map from Map.wz and cached in
 * a static map. A cold map is warmed on a daemon thread and {@link #ladders} returns an empty
 * list for that tick, so the per-session action executor never blocks on a WZ parse (ropes simply
 * become available from the next tick on).
 */
public final class AgentPathing {
    private static final Logger log = LoggerFactory.getLogger(AgentPathing.class);

    /** Max height (px) a jump can mount — one platform of the common 60–90px WZ spacing. */
    static final int JUMP_HEIGHT_PX = 100;
    /** Max horizontal gap (px) a running jump clears (~4 tiles of airtime at walk speed). */
    static final int JUMP_GAP_PX = 120;
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
        FALL, JUMP, CLIMB
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
     * {@code AgentMovement.moveToSmart} when its direct step stalled. Recomputes the route from
     * the agent's current position every tick (bounded BFS), so no route state can go stale and
     * an intercepted manoeuvre self-corrects. If no route exists the agent simply stays put.
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
        if (footholds.isEmpty() || footholds.size() > MAX_PATH_FOOTHOLDS) {
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
        List<Hop> route = findRoute(footholds, ladders, current, ground, onLadder, target);
        if (route.isEmpty()) {
            return false;
        }
        executeHop(session, tree, route.get(0), current, ground, maxStep);
        return true;
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
            case JUMP -> {
                if (ground != null && Math.abs(current.x - hop.viaX()) > CLIMB_REACH_PX) {
                    // Walk to the takeoff column first; the jump fires on a later tick.
                    moveAlongToward(session, ground, current.x, hop.viaX(), maxStep);
                    return;
                }
                // One scripted jump arc that lands on a real foothold (dest is on the edge
                // foothold by construction, and |dx|, |dy| stay within MAX_STEP).
                AgentMovement.moveTo(session, hop.destX(), hop.destY(), maxStep,
                        hop.destX() < current.x
                                ? AgentMovement.JUMP_STANCE_LEFT : AgentMovement.JUMP_STANCE_RIGHT);
            }
            case FALL -> {
                if (ground != null && Math.abs(current.x - hop.viaX()) > 2) {
                    moveAlongToward(session, ground, current.x, hop.viaX(), maxStep);
                    return;
                }
                // Straight down to whatever is actually below (findBelow may pick an
                // intermediate platform; the route recomputes next tick and adapts).
                Foothold below = tree.findBelow(new Point(current.x, current.y + 1));
                if (below != null && !below.isWall()) {
                    AgentMovement.moveTo(session, current.x, below.calculateFooting(current.x),
                            maxStep);
                }
            }
            case CLIMB -> {
                Ladder ladder = hop.ladder();
                int climbTo = hop.up() ? ladder.top() : ladder.bottom();
                if (ground != null && Math.abs(current.x - ladder.x()) > CLIMB_REACH_PX) {
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
                // Dismount onto the far platform's surface (a small step or a jump off the end).
                AgentMovement.moveTo(session, hop.destX(), hop.destY(), maxStep,
                        hop.destY() < current.y
                                ? AgentMovement.JUMP_STANCE_RIGHT : AgentMovement.STANCE_AUTO);
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
