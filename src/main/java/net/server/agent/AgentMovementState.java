package net.server.agent;

import java.awt.Point;
import java.util.List;

/**
 * Per-session movement manoeuvre state (Tier-2 Phase 4, hand-off §4.20). When the direct step
 * stalls, {@link AgentPathing#routeStep} computes a route once and stores it here; subsequent
 * ticks <em>continue</em> the stored manoeuvres (walk-to-takeoff → arc → landing → …) instead of
 * re-picking a fresh route every tick — the re-pick race that made agents dither at platform
 * edges. State is invalidated by a moved target (beyond jitter tolerance), a map change / follow
 * warp, or a stall (no positional progress), after which the next stall re-routes from scratch.
 *
 * <p>Only touched on the session's action executor, so plain synchronization suffices. Harness
 * and unit tests that drive {@code AgentMovement} through mocked sessions have no state (the
 * mocked {@code movementState()} returns {@code null}) and get the stateless legacy behaviour.
 */
public final class AgentMovementState {
    /** How far the goal may drift (px) before the manoeuvre is re-picked — follow targets jitter. */
    private static final int TARGET_TOLERANCE_PX = 30;
    /** Consecutive ticks without positional progress before a manoeuvre is abandoned. */
    private static final int STALL_TICKS = 4;

    private Point target;
    private int mapId = -1;
    private List<AgentPathing.Hop> route;
    private int index;
    private Point lastPosition;
    private int unchangedTicks;

    /** Whether a manoeuvre sequence is active and not yet exhausted. */
    public synchronized boolean hasRoute() {
        return route != null && index < route.size();
    }

    /** Replaces any state with a fresh route towards {@code target} on {@code mapId}. */
    public synchronized void start(List<AgentPathing.Hop> route, Point target, int mapId) {
        this.route = List.copyOf(route);
        this.index = 0;
        this.target = new Point(target);
        this.mapId = mapId;
        this.lastPosition = null;
        this.unchangedTicks = 0;
    }

    /**
     * Validates the state against the current tick: clears it when the target moved beyond
     * jitter tolerance or the agent changed maps (follow warp, portal, respawn) — the two ways a
     * stored manoeuvre can go stale. Returns whether a valid route remains.
     */
    public synchronized boolean validate(int mapId, Point target) {
        if (!hasRoute()) {
            return false;
        }
        if (this.mapId != mapId || target == null) {
            clear();
            return false;
        }
        if (target.distanceSq(this.target) > (long) TARGET_TOLERANCE_PX * TARGET_TOLERANCE_PX) {
            clear();
            return false;
        }
        return true;
    }

    /** The manoeuvre currently being executed. Only call when {@link #hasRoute()} is true. */
    public synchronized AgentPathing.Hop currentHop() {
        return route.get(index);
    }

    /** Marks the current manoeuvre complete; clears the state when the route is exhausted. */
    public synchronized void advance() {
        if (route == null) {
            return;
        }
        index++;
        if (index >= route.size()) {
            clear();
        }
    }

    /**
     * Stall detection: {@code true} when the agent has not changed position for
     * {@link #STALL_TICKS} consecutive route ticks (the manoeuvre is wedged and must be
     * re-routed or abandoned).
     */
    public synchronized boolean stalled(Point position) {
        if (position.equals(lastPosition)) {
            return ++unchangedTicks >= STALL_TICKS;
        }
        lastPosition = new Point(position);
        unchangedTicks = 0;
        return false;
    }

    /** Drops all manoeuvre state. */
    public synchronized void clear() {
        route = null;
        index = 0;
        target = null;
        lastPosition = null;
        unchangedTicks = 0;
    }
}
