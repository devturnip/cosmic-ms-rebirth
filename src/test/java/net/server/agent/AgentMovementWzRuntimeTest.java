package net.server.agent;

import client.Character;
import net.opcodes.RecvOpcode;
import net.packet.InPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProvider;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runtime falsification of {@link AgentMovement#moveToSmart} against <em>real</em> WZ map geometry.
 *
 * <p>Footholds are loaded exactly the way {@code server.maps.MapFactory} does it (Map.wz map image
 * -&gt; foothold category -&gt; foothold nodes) and injected into a mocked {@link MapleMap}, while
 * {@code Character.getPosition()} is backed by a mutable holder so the production movement code can
 * advance across simulated ticks. After every synthesized {@code MOVE_PLAYER} step the test checks
 * the invariant behind the "agents walk in the air" fix:
 * <ul>
 *   <li><b>(A)</b> if the previous position was unsupported (no non-wall foothold within 20px under
 *       the feet), the emitted position must not translate sideways or climb:
 *       {@code x == prevX && y >= prevY};</li>
 *   <li><b>(B)</b> the emitted position is within one clamped step of the previous one (no
 *       teleport) and inside 16-bit range.</li>
 * </ul>
 *
 * <p>Skips gracefully (via {@link org.junit.jupiter.api.Assumptions#assumeTrue}) when the WZ tree is
 * unavailable, mirroring {@code CompanionTemplateWzTest}.
 */
class AgentMovementWzRuntimeTest {
    /** Real, multi-platform Victoria Island maps; each is verified to actually load. */
    private static final List<Integer> MAP_IDS = List.of(
            100000000, // Henesys
            104000000, // Lith Harbor
            101000000, // Henesys Hunting Ground
            102000000, // Perion
            120000000, // Nautilus
            103000000); // Kerning City
    /** Fixed seed so any counterexample is deterministic and reproducible. */
    private static final long SEED = 0xC0FFEEL;
    /** Positive/negative gap (px) within which a foothold is treated as supporting the agent. */
    private static final int SUPPORT_TOLERANCE = 20;
    /** Number of random steps simulated per map. */
    private static final int STEPS_PER_MAP = 1000;
    private static final int HEADER_BYTES = 9;

    private static DataProvider mapSource;

    @BeforeAll
    static void loadMapWz() {
        Path mapWz = locateMapWz();
        if (mapWz != null) {
            mapSource = new XMLWZFile(mapWz);
        }
    }

    /**
     * Random-walk stress test: seed on real foothold surfaces, aim at other platforms (forcing
     * cross-platform, upward and downward moves), and validate the airborne invariant after every
     * emitted step.
     */
    @Test
    void moveToSmartNeverWalksInTheAirOnRealMaps() {
        assumeTrue(mapSource != null, "WZ Map.wz unavailable");

        List<String> counterexamples = new ArrayList<>();
        List<Integer> loadedMaps = new ArrayList<>();
        List<Integer> skippedMaps = new ArrayList<>();
        long footholdCount = 0;
        long steps = 0;
        long airborne = 0;
        long descents = 0;
        long transitions = 0;

        Point[] emitted = new Point[1];
        for (int mapId : MAP_IDS) {
            FootholdTree tree = loadFootholds(mapId);
            List<Foothold> platforms = tree == null ? List.of() : standablePlatforms(tree);
            if (platforms.isEmpty()) {
                skippedMaps.add(mapId);
                continue;
            }
            loadedMaps.add(mapId);
            footholdCount += platforms.size();

            Random rng = new Random(SEED ^ (0x9E3779B97F4A7C15L * mapId));
            int[] position = new int[2];
            seed(rng, platforms, position);
            AgentSession session = sessionFor(tree, position, emitted);

            int idle = 0;
            for (int step = 0; step < STEPS_PER_MAP; step++) {
                Point prev = new Point(position[0], position[1]);
                Foothold prevSupport = footholdAt(tree, prev.x, prev.y);
                Point target = pickTarget(rng, platforms, prev.y);

                emitted[0] = null;
                AgentMovement.moveToSmart(session, target, AgentMovement.MAX_STEP);

                Point next = emitted[0] == null ? prev : emitted[0];
                if (emitted[0] != null) {
                    position[0] = next.x;
                    position[1] = next.y;
                }

                steps++;
                if (prevSupport == null) {
                    airborne++;
                    // The fix: an airborne agent may only fall straight down, never drift or climb.
                    if (next.x != prev.x || next.y < prev.y) {
                        counterexamples.add(String.format(
                                "map=%d from=(%d,%d) target=(%d,%d) emitted=(%d,%d) supportedBefore=false supportedAfter=%s",
                                mapId, prev.x, prev.y, target.x, target.y, next.x, next.y,
                                footholdAt(tree, next.x, next.y) != null));
                    }
                }
                if (next.y > prev.y) {
                    descents++;
                }
                // (B) no teleport: moveTo clamps each axis to maxStep.
                if (Math.abs(next.x - prev.x) > AgentMovement.MAX_STEP
                        || Math.abs(next.y - prev.y) > AgentMovement.MAX_STEP) {
                    counterexamples.add(String.format(
                            "map=%d TELEPORT from=(%d,%d) emitted=(%d,%d)",
                            mapId, prev.x, prev.y, next.x, next.y));
                }
                // (B) 16-bit range (redundant with readShort, kept as an explicit guard).
                assertTrue(next.x >= Short.MIN_VALUE && next.x <= Short.MAX_VALUE
                                && next.y >= Short.MIN_VALUE && next.y <= Short.MAX_VALUE,
                        "emitted position out of 16-bit range: " + next);

                Foothold nextSupport = footholdAt(tree, next.x, next.y);
                if (prevSupport != null && nextSupport != null && prevSupport != nextSupport) {
                    transitions++;
                }

                if (next.equals(prev)) {
                    idle++;
                } else {
                    idle = 0;
                }
                if (idle >= 12) { // unreachable target: re-home the agent onto a new surface
                    seed(rng, platforms, position);
                    idle = 0;
                }
            }
        }

        System.out.printf(
                "[AgentMovementWzRuntimeTest] loadedMaps=%s skippedMaps=%s footholds=%d steps=%d airborne=%d descents=%d transitions=%d%n",
                loadedMaps, skippedMaps, footholdCount, steps, airborne, descents, transitions);

        assertTrue(loadedMaps.size() >= 4,
                "expected at least 4 real maps to load, got " + loadedMaps + " (skipped " + skippedMaps + ")");
        assertTrue(steps > 0, "no steps simulated");
        assertTrue(airborne > 0, "random walk never exercised the airborne fall branch");
        assertTrue(counterexamples.isEmpty(),
                "agents walked in the air on real maps:\n" + String.join("\n", counterexamples));
    }

    /**
     * Deterministic airborne check: suspend the agent 40px above each real foothold (unsupported)
     * and pull it sideways and upward. The emitted step must be a pure vertical fall.
     */
    @Test
    void suspendedAgentsFallStraightDownOnRealMaps() {
        assumeTrue(mapSource != null, "WZ Map.wz unavailable");

        List<String> counterexamples = new ArrayList<>();
        int situations = 0;
        int loadedMaps = 0;
        int perMapLimit = 30;
        Point[] emitted = new Point[1];
        for (int mapId : MAP_IDS) {
            FootholdTree tree = loadFootholds(mapId);
            List<Foothold> platforms = tree == null ? List.of() : standablePlatforms(tree);
            if (platforms.isEmpty()) {
                continue;
            }
            loadedMaps++;
            int[] position = new int[2];
            AgentSession session = sessionFor(tree, position, emitted);
            int perMap = 0;
            for (Foothold fh : platforms) {
                if (perMap >= perMapLimit) {
                    break;
                }
                int minX = Math.min(fh.getX1(), fh.getX2());
                int maxX = Math.max(fh.getX1(), fh.getX2());
                if (maxX - minX < 4) {
                    continue;
                }
                int x = (minX + maxX) / 2;
                int y = fh.calculateFooting(x) - 40; // 40px above this surface...
                if (footholdAt(tree, x, y) != null) {
                    continue; // ...but some other foothold actually supports it: not airborne
                }
                position[0] = x;
                position[1] = y;
                Point target = new Point(x + 400, y - 200); // lure sideways and upward
                emitted[0] = null;
                AgentMovement.moveToSmart(session, target, AgentMovement.MAX_STEP);
                situations++;
                Point next = emitted[0] == null ? new Point(x, y) : emitted[0];
                if (next.x != x || next.y < y) {
                    counterexamples.add(String.format(
                            "map=%d from=(%d,%d) target=(%d,%d) emitted=(%d,%d) footholdsUnderX=%s",
                            mapId, x, y, target.x, target.y, next.x, next.y, footholdsUnderX(tree, x)));
                }
                perMap++;
            }
        }

        System.out.printf("[AgentMovementWzRuntimeTest] suspendedSituations=%d maps=%d%n", situations, loadedMaps);
        assertTrue(situations > 0, "no suspended situations exercised");
        assertTrue(counterexamples.isEmpty(),
                "suspended agents moved sideways/climbed through the air:\n" + String.join("\n", counterexamples));
    }

    // ---------------------------------------------------------------------------------------------
    // Real WZ loading (mirrors server.maps.MapFactory)
    // ---------------------------------------------------------------------------------------------

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

    /** Same node path as {@code MapFactory.getMapName}. */
    private static String mapName(int mapId) {
        String name = String.format("%09d", mapId);
        return "Map/Map" + (mapId / 100000000) + "/" + name + ".img";
    }

    /** Builds a {@link FootholdTree} from real WZ data exactly like {@code MapFactory} does. */
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

        List<Foothold> allFootholds = new ArrayList<>();
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
                    fh.setPrev(DataTool.getInt(footHold.getChildByPath("prev")));
                    fh.setNext(DataTool.getInt(footHold.getChildByPath("next")));
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
                    allFootholds.add(fh);
                }
            }
        }
        if (allFootholds.isEmpty()) {
            return null;
        }
        FootholdTree tree = new FootholdTree(lBound, uBound);
        for (Foothold fh : allFootholds) {
            tree.insert(fh);
        }
        return tree;
    }

    // ---------------------------------------------------------------------------------------------
    // Foothold / simulation helpers
    // ---------------------------------------------------------------------------------------------

    private static List<Foothold> standablePlatforms(FootholdTree tree) {
        List<Foothold> platforms = new ArrayList<>();
        for (Foothold fh : tree.getAllFootholds()) {
            if (!fh.isWall() && fh.getX1() != fh.getX2()) {
                platforms.add(fh);
            }
        }
        return platforms;
    }

    /** The non-wall foothold supporting {@code (x,y)} under the 20px tolerance, or {@code null}. */
    private static Foothold footholdAt(FootholdTree tree, int x, int y) {
        Foothold best = null;
        int bestGap = SUPPORT_TOLERANCE + 1;
        for (Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (x < minX || x > maxX) {
                continue;
            }
            int gap = Math.abs(fh.calculateFooting(x) - y);
            if (gap <= SUPPORT_TOLERANCE && gap < bestGap) {
                best = fh;
                bestGap = gap;
            }
        }
        return best;
    }

    /** Human-readable list of non-wall footholds whose x-range covers {@code x}, sorted by y. */
    private static String footholdsUnderX(FootholdTree tree, int x) {
        List<String> rows = new ArrayList<>();
        for (Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall() || x < Math.min(fh.getX1(), fh.getX2()) || x > Math.max(fh.getX1(), fh.getX2())) {
                continue;
            }
            rows.add(String.format("id=%d y=%d [%d..%d]", fh.getId(), fh.calculateFooting(x),
                    Math.min(fh.getX1(), fh.getX2()), Math.max(fh.getX1(), fh.getX2())));
        }
        rows.sort(null);
        return rows.toString();
    }

    private static void seed(Random rng, List<Foothold> platforms, int[] position) {
        Foothold fh = platforms.get(rng.nextInt(platforms.size()));
        int minX = Math.min(fh.getX1(), fh.getX2());
        int maxX = Math.max(fh.getX1(), fh.getX2());
        int x = minX + rng.nextInt(maxX - minX + 1);
        position[0] = x;
        position[1] = fh.calculateFooting(x);
    }

    /** Picks a target on another platform, biasing toward tall descents to force airborne frames. */
    private static Point pickTarget(Random rng, List<Foothold> platforms, int prevY) {
        int mode = rng.nextInt(100);
        for (int attempt = 0; attempt < 60; attempt++) {
            Foothold fh = platforms.get(rng.nextInt(platforms.size()));
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (minX >= maxX) {
                continue;
            }
            int x = minX + rng.nextInt(maxX - minX + 1);
            int y = fh.calculateFooting(x);
            if (mode < 50) {
                // A large share of downward targets must be taller than a single clamped step.
                int minDrop = mode < 25 ? AgentMovement.MAX_STEP + 120 : 30;
                if (y <= prevY + minDrop) {
                    continue;
                }
            } else if (mode < 65 && y >= prevY - 30) {
                continue; // force an ascent attempt
            }
            return new Point(x, y);
        }
        Foothold fh = platforms.get(rng.nextInt(platforms.size()));
        int minX = Math.min(fh.getX1(), fh.getX2());
        int maxX = Math.max(fh.getX1(), fh.getX2());
        int x = minX + rng.nextInt(maxX - minX + 1);
        return new Point(x, fh.calculateFooting(x));
    }

    /**
     * Mocked session whose {@code Character.getPosition()} reads from {@code position}, and whose
     * {@code MOVE_PLAYER} dispatch parses the emitted absolute coordinates into {@code emitted[0]}.
     */
    private static AgentSession sessionFor(FootholdTree tree, int[] position, Point[] emitted) {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentSession session = mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(chr.getPosition()).thenAnswer(invocation -> new Point(position[0], position[1]));
        when(chr.getMap()).thenReturn(map);
        when(map.getMapArea()).thenReturn(null); // fall back to 16-bit clamping only
        when(map.getFootholds()).thenReturn(tree);
        doAnswer(invocation -> {
            InPacket packet = invocation.getArgument(1);
            packet.skip(HEADER_BYTES);
            packet.skip(1); // numCommands
            packet.skip(1); // command type (0 = absolute)
            emitted[0] = new Point(packet.readShort(), packet.readShort());
            return true;
        }).when(session).dispatchInbound(any(RecvOpcode.class), any(InPacket.class));
        return session;
    }
}
