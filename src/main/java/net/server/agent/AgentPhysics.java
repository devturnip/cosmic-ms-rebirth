package net.server.agent;

import server.maps.Foothold;

import java.awt.Point;
import java.util.List;

/**
 * Real v83 movement physics for agent pathing, taken from the server's own WZ data
 * ({@code wz/Map.wz/Physics.img.xml}: {@code gravityAcc=2000}, {@code jumpSpeed=555},
 * {@code walkSpeed=125}, {@code fallSpeed=670}). All other quantities are <b>derived</b> from
 * these four — no magic numbers:
 * <ul>
 *   <li>jump apex {@code v0²/2g = 555²/4000 ≈ 77px} — one platform of the common 60–90px
 *       WZ spacing;</li>
 *   <li>flat-jump airtime {@code 2v0/g ≈ 0.555s};</li>
 *   <li>flat-jump reach {@code walkSpeed · airtime ≈ 69px} — a flat gap wider than that is
 *       physically unjumpable and must not be routed;</li>
 *   <li>a jump landing {@code d} pixels lower stays airborne for
 *       {@code (v0 + √(v0² + 2gd))/g}, so dropping extends the reach (up to ~94px for a
 *       150px drop).</li>
 * </ul>
 *
 * <p><b>Arc modelling.</b> {@link #simulateJump} integrates the parabola
 * {@code y(t) = y0 - v0·t + g/2·t²} (y grows downward) from a snapped stand point and accepts a
 * candidate landing only when the arc <em>descends onto</em> the target foothold's surface within
 * its x-range, never clipping through an intervening foothold on the way (rising <em>through</em>
 * a platform is allowed — MapleStory platforms are pass-through from below). This replaces the
 * old "predict a landing point and hope" jump edges.
 */
final class AgentPhysics {
    /** v83 gravity, px/s² ({@code Physics.img.xml gravityAcc}). */
    static final double GRAVITY_PX_PER_S2 = 2000.0;
    /** v83 jump velocity, px/s upward ({@code Physics.img.xml jumpSpeed}). */
    static final double JUMP_VELOCITY_PX_PER_S = 555.0;
    /** v83 walk speed, px/s ({@code Physics.img.xml walkSpeed}; mirrors AgentMovement's pace). */
    static final double WALK_SPEED_PX_PER_S = 125.0;
    /** v83 terminal fall speed, px/s ({@code Physics.img.xml fallSpeed}). */
    static final double FALL_SPEED_PX_PER_S = 670.0;

    /** Apex of a v83 jump: {@code v0²/(2g)} ≈ 77px. */
    static final double JUMP_APEX_PX =
            JUMP_VELOCITY_PX_PER_S * JUMP_VELOCITY_PX_PER_S / (2 * GRAVITY_PX_PER_S2);
    /** Airtime of a jump that lands at its takeoff height: {@code 2v0/g} ≈ 0.555s. */
    static final double JUMP_FLAT_AIRTIME_S = 2 * JUMP_VELOCITY_PX_PER_S / GRAVITY_PX_PER_S2;
    /** Horizontal reach of a flat jump at walk speed: {@code walkSpeed · airtime} ≈ 69px. */
    static final double JUMP_FLAT_RANGE_PX = WALK_SPEED_PX_PER_S * JUMP_FLAT_AIRTIME_S;

    /** Simulation time step (the client's 60fps animation rate). */
    private static final double DT = 1.0 / 60.0;
    /** A jump arc that has not landed within this long never will. */
    private static final double MAX_JUMP_TIME_S = 2.0;
    /** Surface tolerance when deciding that the arc has reached the landing surface. */
    private static final int LANDING_TOLERANCE_PX = 2;
    /** How far the arc may sink below an intervening surface before the jump counts as clipped. */
    private static final int CLIP_TOLERANCE_PX = 3;
    /** Deepest landing modelled as a jump (beyond that the manoeuvre is a fall, not a jump). */
    static final int MAX_JUMP_DROP_PX = 150;

    private AgentPhysics() {
    }

    /** A validated jump landing: the touch-down point and the total airtime. */
    record Landing(int x, int y, double airtime) {
    }

    /**
     * Time a jump that lands {@code dropPx} pixels <em>below</em> its takeoff stays airborne
     * (the descending root of {@code y0 - v0·t + g/2·t² = y0 + dropPx}).
     */
    static double airtimeForDrop(double dropPx) {
        double discriminant = JUMP_VELOCITY_PX_PER_S * JUMP_VELOCITY_PX_PER_S
                + 2 * GRAVITY_PX_PER_S2 * Math.max(0, dropPx);
        return (JUMP_VELOCITY_PX_PER_S + Math.sqrt(discriminant)) / GRAVITY_PX_PER_S2;
    }

    /** Furthest horizontal reach of a jump landing {@code dropPx} pixels below takeoff. */
    static double jumpRangeForDrop(double dropPx) {
        return WALK_SPEED_PX_PER_S * airtimeForDrop(dropPx);
    }

    /**
     * Simulates a v83 jump arc from {@code takeoff} (a snapped stand point) towards
     * {@code target}'s surface and returns the landing point, or {@code null} when the arc never
     * lands on the target or clips through an intervening foothold.
     *
     * @param direction      -1 (left), 0 (straight up) or +1 (right); horizontal velocity is
     *                       {@code direction · walkSpeed} for the whole arc
     * @param takeoffGround  the foothold the arc takes off from (exempt from clip checks)
     * @param allFootholds   every non-wall foothold of the map (for clip checks)
     */
    static Landing simulateJump(Point takeoff, int direction, Foothold target,
            Foothold takeoffGround, List<Foothold> allFootholds) {
        if (target == null || target.isWall()) {
            return null;
        }
        int targetMin = Math.min(target.getX1(), target.getX2());
        int targetMax = Math.max(target.getX1(), target.getX2());
        double vx = direction * WALK_SPEED_PX_PER_S;
        double prevY = takeoff.y;
        for (double t = DT; t <= MAX_JUMP_TIME_S; t += DT) {
            double x = takeoff.x + vx * t;
            double y = takeoff.y - JUMP_VELOCITY_PX_PER_S * t
                    + 0.5 * GRAVITY_PX_PER_S2 * t * t;
            if (y - takeoff.y > MAX_JUMP_DROP_PX + JUMP_APEX_PX) {
                return null; // fell far past any jump-shaped landing: never reaching the target
            }
            // Landing: the arc descends onto the target's surface inside its x-range. Rising
            // through it is allowed (MapleStory platforms are pass-through from below).
            if (x >= targetMin && x <= targetMax) {
                double surface = target.calculateFooting((int) Math.round(x));
                if (prevY <= surface + LANDING_TOLERANCE_PX
                        && y > surface - LANDING_TOLERANCE_PX
                        && t > DT) {
                    int landX = Math.max(targetMin, Math.min(targetMax, (int) Math.round(x)));
                    return new Landing(landX, target.calculateFooting(landX), t);
                }
            }
            // Clip check: the arc must not descend through any intervening foothold's surface.
            for (Foothold fh : allFootholds) {
                if (fh.isWall() || fh == target || fh == takeoffGround) {
                    continue;
                }
                int minX = Math.min(fh.getX1(), fh.getX2());
                int maxX = Math.max(fh.getX1(), fh.getX2());
                if (x < minX || x > maxX) {
                    continue;
                }
                double surface = fh.calculateFooting((int) Math.round(x));
                if (prevY <= surface + CLIP_TOLERANCE_PX && y > surface + CLIP_TOLERANCE_PX) {
                    return null; // the arc fell through a platform between takeoff and landing
                }
            }
            prevY = y;
        }
        return null;
    }

    /** Time to fall {@code dropPx} pixels under gravity (used as an A* edge cost). */
    static double fallTime(double dropPx) {
        double freeFall = Math.sqrt(2 * Math.max(0, dropPx) / GRAVITY_PX_PER_S2);
        double terminal = Math.max(0, dropPx) / FALL_SPEED_PX_PER_S;
        return Math.max(freeFall, terminal);
    }
}
