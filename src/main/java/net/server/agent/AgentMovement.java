package net.server.agent;

import client.Character;
import io.netty.buffer.Unpooled;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.Portal;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Helpers for moving an agent around its map. Movement is performed by synthesizing a
 * {@code MOVE_PLAYER} packet (a single absolute movement command) and dispatching it through
 * {@code MovePlayerHandler}, so the normal position/stance update and map broadcast happen.
 * <p>
 * Coordinates are clamped to the map area and to 16-bit range, and a single action can only
 * advance the agent by {@link #MAX_STEP} pixels, so a hallucinated destination can never
 * teleport the agent across the map.
 */
public final class AgentMovement {
    private static final int WALK_STANCE_RIGHT = 2;
    private static final int WALK_STANCE_LEFT = 3;
    /** Idle/stand stances (see {@code StatEffect}): even = facing right, odd = facing left. */
    private static final int STAND_STANCE_RIGHT = 4;
    private static final int STAND_STANCE_LEFT = 5;
    /** Rope/ladder climb stance (captured from a real client climbing). */
    static final int CLIMB_STANCE = 16;
    /** Jump stances (same parity scheme as walk/stand: even = facing right, odd = facing left). */
    static final int JUMP_STANCE_RIGHT = 6;
    static final int JUMP_STANCE_LEFT = 7;
    /** Sentinel for {@link #moveTo(AgentSession, int, int, int, int)}: derive the stance from the step. */
    static final int STANCE_AUTO = -1;
    /**
     * Base walk speed in pixels/second. The movement fragment's duration is derived from the step
     * distance at this speed (a fixed 120 ms made every step a short dash), and the fragment
     * velocity is the same value, so clients interpolate at a normal walking pace.
     */
    private static final double WALK_SPEED_PX_PER_SEC = 125.0;
    static final int MAX_STEP = 150;
    /** Vertical gap beyond which two positions are treated as different platforms. */
    static final int PLATFORM_TOLERANCE = 80;
    /** Vertical gap beyond which two positions are treated as different platforms (squared). */
    static final int PLATFORM_TOLERANCE_SQ = PLATFORM_TOLERANCE * PLATFORM_TOLERANCE;
    /** Horizontal distance at or below which the agent is treated as aligned for a drop. */
    private static final int PLATFORM_ALIGN_TOLERANCE = 20;
    /** How far above its support surface an agent may sit and still count as standing on it. */
    static final int GROUNDED_EPSILON = 8;
    /** Coarse sampling granularity (px) when probing the ground ahead of a walking agent. */
    private static final int WALK_SAMPLE_STEP = 8;
    /** Distance (squared) within which a portal can be entered (~60px). */
    static final int PORTAL_ENTER_RANGE_SQ = 60 * 60;

    private AgentMovement() {
    }

    /** Moves the agent one ground-aware step toward {@code target}. */
    public static void stepToward(AgentSession session, Point current, Point target, int maxStep) {
        if (current == null || target == null) {
            return;
        }
        moveToSmart(session, target, maxStep);
    }

    /**
     * Ground-aware step. An agent must never leave the ground it can actually stand on:
     * <ul>
     *   <li>When it is airborne (more than {@link #GROUNDED_EPSILON} above its support) it falls
     *       <em>straight down</em> to the next foothold beneath its current x instead of drifting
     *       sideways through the air (the old behaviour, which made agents look like they walked on
     *       air).</li>
     *   <li>When the target is on the same level or higher it walks horizontally over whatever
     *       foothold is under the next step and stops at the platform edge.</li>
     *   <li>When the target is on a lower platform it walks to the drop point on its current
     *       foothold, then descends onto the landing foothold (falling straight down when the two
     *       platforms do not overlap horizontally).</li>
     * </ul>
     * When the direct step <em>stalls</em> (platform edge, gap, or a target it cannot reach by
     * walking and dropping alone) the foothold/rope pathfinder {@link AgentPathing#routeStep} takes
     * over for this tick: it routes around gaps via short jump arcs that land on real footholds and
     * climbs real ropes/ladders — one clamped step per tick, never through open air otherwise.
     * When the map exposes no footholds (odd maps, tests) the previous per-axis behaviour is kept.
     * All movement is still clamped per axis by {@link #MAX_STEP}, so a hallucinated destination can
     * never teleport.
     */
    public static void moveToSmart(AgentSession session, Point target, int maxStep) {
        if (AgentPathing.hangingOnLadder(session)) {
            // Mid-climb: the direct step would read the rope position as unsupported and fall.
            // Keep executing the climb route instead (continue / dismount).
            AgentPathing.routeStep(session, target, maxStep);
            return;
        }
        if (!directStep(session, target, maxStep)) {
            AgentPathing.routeStep(session, target, maxStep);
        }
    }

    /**
     * The direct ground-aware step (the fast path). Returns {@code true} when the agent arrived,
     * is airborne, or emitted a move; {@code false} only when it is grounded and stalled with no
     * direct move to make — which is when {@link AgentPathing} gets the tick.
     */
    private static boolean directStep(AgentSession session, Point target, int maxStep) {
        Character chr = session.getPlayer();
        if (chr == null || target == null) {
            return true;
        }
        Point current = chr.getPosition();
        if (current == null) {
            return true;
        }
        FootholdTree tree = chr.getMap() == null ? null : chr.getMap().getFootholds();
        if (tree == null) {
            plainStep(session, current, target, maxStep);
            return true;
        }

        Foothold ground = footholdAt(tree, current);
        boolean grounded = ground != null
                && current.y >= ground.calculateFooting(current.x) - GROUNDED_EPSILON;
        if (!grounded) {
            // Airborne: fall straight down onto the first foothold under the current x. Never
            // translate horizontally while unsupported, or the agent appears to walk on air.
            Foothold below = tree.findBelow(new Point(current.x, current.y + 1));
            if (below != null && !below.isWall() && below.calculateFooting(current.x) > current.y) {
                moveTo(session, current.x, below.calculateFooting(current.x), maxStep);
            }
            // Otherwise there is nothing to land on: hold position rather than drift sideways in
            // the air (which is what made agents look like they were walking on air).
            return true; // airborne agents hold or fall; routing never runs mid-air
        }

        int dx = target.x - current.x;
        int dy = target.y - current.y;
        if (Math.abs(dx) <= PLATFORM_ALIGN_TOLERANCE && Math.abs(dy) <= PLATFORM_ALIGN_TOLERANCE) {
            return true;
        }

        Foothold landing = footholdAt(tree, target);
        if (landing == null) {
            landing = tree.findBelow(target); // target mid-jump: use the ground under it
        }
        if (landing == null) {
            return walkOnGround(session, tree, ground, current, target.x, maxStep);
        }
        int landingMin = Math.min(landing.getX1(), landing.getX2());
        int landingMax = Math.max(landing.getX1(), landing.getX2());
        int landingX = Math.max(landingMin, Math.min(landingMax, target.x));
        int landingY = landing.calculateFooting(landingX);
        if (landingY <= current.y + PLATFORM_ALIGN_TOLERANCE) {
            // Same level or higher: walk along the ground under the next step; the direct step
            // itself never ascends (a deliberate ascent is a pathfinder jump arc or rope climb).
            // A strictly higher platform, though, can never be reached by walking alone: prefer a
            // jump-arc/rope route; without one, drift along the current platform toward the target
            // column (the pre-pathing behaviour — never ascending through the air).
            if (landing != ground && landingY < current.y - PLATFORM_ALIGN_TOLERANCE) {
                return AgentPathing.routeStep(session, target, maxStep)
                        || walkOnGround(session, tree, ground, current, target.x, maxStep);
            }
            return walkOnGround(session, tree, ground, current, target.x, maxStep);
        }

        // The target is on a lower platform: close the horizontal gap on the current foothold so we
        // drop as close to the target as the platform allows, then descend onto the landing surface.
        int groundMin = Math.min(ground.getX1(), ground.getX2());
        int groundMax = Math.max(ground.getX1(), ground.getX2());
        int approachX = Math.max(groundMin, Math.min(groundMax, target.x));
        if (Math.abs(approachX - current.x) > PLATFORM_ALIGN_TOLERANCE) {
            // If nothing can be landed on from the approach column the drop would stall there:
            // prefer a rope/jump route now instead of walking to a dead column and back.
            if (tree.findBelow(new Point(approachX, current.y + 1)) == null
                    && AgentPathing.routeStep(session, target, maxStep)) {
                return true;
            }
            moveAlong(session, ground, current.x, approachX, maxStep);
            return true;
        }
        if (approachX >= landingMin && approachX <= landingMax) {
            // The platforms overlap here: step directly onto the landing surface.
            moveAlong(session, landing, current.x, landingX, maxStep);
            return true;
        }
        // A horizontal gap separates the platforms: fall straight down to the next foothold below.
        Foothold below = tree.findBelow(new Point(current.x, current.y + 1));
        if (below == null || below.isWall()) {
            return false; // nothing to land on: give the pathfinder a chance (rope/jump route)
        }
        moveTo(session, current.x, below.calculateFooting(current.x), maxStep);
        return true;
    }

    /**
     * Walks one step toward {@code targetX} along the ground. The ground ahead is sampled: the agent
     * advances only as far as a foothold remains under its feet, so it stops at a platform edge or a
     * gap instead of walking into the air. {@link #standableAt} only considers surfaces at or below
     * the feet, so a horizontal walk can never ascend onto a higher platform.
     *
     * @return whether a step was emitted ({@code false} = stalled at an edge/gap)
     */
    private static boolean walkOnGround(AgentSession session, FootholdTree tree, Foothold ground,
            Point current, int targetX, int maxStep) {
        int dx = targetX - current.x;
        if (dx == 0) {
            return false;
        }
        int step = Math.max(1, maxStep);
        int dir = Integer.signum(dx);
        int limit = Math.min(Math.abs(dx), step);
        int reached = 0;
        Foothold reachedGround = ground;
        while (reached < limit) {
            int next = Math.min(reached + WALK_SAMPLE_STEP, limit);
            Foothold probe = standableAt(tree, current.x + dir * next, current.y);
            if (probe == null) {
                // Refine to the exact edge between the last good sample and the gap.
                for (int fine = reached + 1; fine < next; fine++) {
                    Foothold finer = standableAt(tree, current.x + dir * fine, current.y);
                    if (finer == null) {
                        break;
                    }
                    reached = fine;
                    reachedGround = finer;
                }
                break;
            }
            reached = next;
            reachedGround = probe;
        }
        if (reached == 0) {
            return false; // the very next ground is already missing: stay put
        }
        int destX = current.x + dir * reached;
        moveTo(session, destX, reachedGround.calculateFooting(destX), maxStep);
        return true;
    }

    /**
     * The foothold the agent can place its feet on at column {@code x} when its feet are at
     * {@code referenceY}, or {@code null}. Uses {@link FootholdTree#findBelow(Point)}, which only
     * returns surfaces at or below {@code referenceY}: a candidate can therefore never be a higher
     * platform (agents do not jump/climb) and never more than {@link #PLATFORM_TOLERANCE} below the
     * feet.
     */
    private static Foothold standableAt(FootholdTree tree, int x, int referenceY) {
        Foothold fh = tree.findBelow(new Point(x, referenceY));
        if (fh == null || fh.isWall()) {
            return null;
        }
        return fh.calculateFooting(x) - referenceY <= PLATFORM_TOLERANCE ? fh : null;
    }

    /** Steps along {@code foothold} from {@code fromX} toward {@code toX}, keeping y on the surface. */
    static void moveAlong(AgentSession session, Foothold foothold, int fromX, int toX, int maxStep) {
        int step = Math.max(1, maxStep);
        int dx = toX - fromX;
        int destX = Math.abs(dx) > step ? fromX + Integer.signum(dx) * step : toX;
        moveTo(session, destX, foothold.calculateFooting(destX), maxStep);
    }

    /** The previous per-axis behaviour, used when the map exposes no usable footholds. */
    private static void plainStep(AgentSession session, Point current, Point target, int maxStep) {
        int dx = target.x - current.x;
        int dy = target.y - current.y;
        if (Math.abs(dx) <= PLATFORM_ALIGN_TOLERANCE && Math.abs(dy) <= PLATFORM_ALIGN_TOLERANCE) {
            return;
        }
        if ((long) dy * dy > PLATFORM_TOLERANCE_SQ && Math.abs(dx) > PLATFORM_ALIGN_TOLERANCE) {
            // Different platform: align horizontally first, then approach vertically.
            int step = Integer.signum(dx) * Math.min(Math.abs(dx), Math.max(1, maxStep));
            moveTo(session, current.x + step, current.y, maxStep);
            return;
        }
        moveTo(session, target.x, target.y, maxStep);
    }

    /**
     * The non-wall foothold the agent is standing on at {@code p}, or {@code null}. Uses
     * {@link FootholdTree#findBelow(Point)} first and only enumerates the tree when the agent sits
     * slightly off the surface (e.g. part-way through a sloped step).
     */
    static Foothold footholdAt(FootholdTree tree, Point p) {
        Foothold below = tree.findBelow(p);
        if (below != null && !below.isWall()
                && Math.abs(below.calculateFooting(p.x) - p.y) <= PLATFORM_TOLERANCE) {
            return below;
        }
        Foothold best = null;
        int bestDistSq = PLATFORM_TOLERANCE_SQ + 1;
        for (Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (p.x < minX || p.x > maxX) {
                continue;
            }
            int gap = fh.calculateFooting(p.x) - p.y;
            // A surface below the feet may be up to PLATFORM_TOLERANCE away (the agent is above it,
            // e.g. part-way through a sloped step). A surface ABOVE the feet is only a rounding
            // recovery, so accept it solely within GROUNDED_EPSILON: otherwise an overhead platform
            // within 80px would be mistaken for ground and the agent would climb sideways onto it.
            int reach = gap < 0 ? GROUNDED_EPSILON : PLATFORM_TOLERANCE;
            int distSq = gap * gap;
            if (Math.abs(gap) <= reach && distSq < bestDistSq) {
                best = fh;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    /**
     * Steps directly away from a threat by up to {@code maxStep} pixels. Routed through
     * {@link #moveToSmart} so a fleeing agent stays on the ground (it will not back into thin air
     * or climb a platform it cannot reach).
     */
    public static void stepAway(AgentSession session, Point self, Point threat, int maxStep) {
        if (self == null || threat == null) {
            return;
        }
        int dx = self.x - threat.x;
        int dy = self.y - threat.y;
        if (dx == 0 && dy == 0) {
            dx = 1;
        }
        double length = Math.hypot(dx, dy);
        int step = Math.max(1, maxStep);
        int targetX = self.x + (int) Math.round(dx / length * step);
        int targetY = self.y + (int) Math.round(dy / length * step);
        moveToSmart(session, new Point(targetX, targetY), step);
    }

    /** Sends a clamped, single-step absolute move toward the given position. */
    public static void moveTo(AgentSession session, int x, int y) {
        moveTo(session, x, y, MAX_STEP);
    }

    /** Sends a clamped step toward the given position, moving at most {@code maxStep} per axis. */
    public static void moveTo(AgentSession session, int x, int y, int maxStep) {
        moveTo(session, x, y, maxStep, STANCE_AUTO);
    }

    /**
     * Sends a clamped step toward the given position, moving at most {@code maxStep} per axis.
     * {@code forcedStance} pins the stance byte (used by {@link AgentPathing} for jump arcs and
     * rope climbs); pass {@code STANCE_AUTO} to derive the stance from the step direction.
     */
    static void moveTo(AgentSession session, int x, int y, int maxStep, int forcedStance) {
        Character chr = session.getPlayer();
        if (chr == null) {
            return;
        }
        int step = Math.max(1, maxStep);
        Point current = chr.getPosition();
        int targetX = x;
        int targetY = y;
        if (current != null) {
            int dx = x - current.x;
            if (Math.abs(dx) > step) {
                targetX = current.x + Integer.signum(dx) * step;
            }
            int dy = y - current.y;
            if (Math.abs(dy) > step) {
                targetY = current.y + Integer.signum(dy) * step;
            }
        }
        Point clamped = clampToMap(chr, targetX, targetY);
        final int fromX = current == null ? clamped.x : current.x;
        final int fromY = current == null ? clamped.y : current.y;
        int stance = forcedStance;
        if (stance == STANCE_AUTO) {
            // Vertical-dominant steps read as rope/ladder climbing; horizontal ones as walking.
            if (Math.abs(clamped.y - fromY) > Math.abs(clamped.x - fromX)) {
                stance = CLIMB_STANCE;
            } else {
                stance = clamped.x < fromX ? WALK_STANCE_LEFT : WALK_STANCE_RIGHT;
            }
        }
        session.dispatchInbound(RecvOpcode.MOVE_PLAYER,
                buildMovePacket(clamped.x, clamped.y, stance, fromX, fromY));
        // Pathing classification: a climb step means the agent is hanging on a rope; any other
        // step (walk, jump, stand, fall) means it is not — position alone is ambiguous when a
        // rope column overlaps platform surfaces.
        session.setClimbingRope(stance == CLIMB_STANCE);
        session.markMoved();
    }

    /**
     * Returns the agent to its idle stance so other clients stop showing the last walk animation.
     * Only does anything while the character is still in a walk stance.
     */
    public static void broadcastIdle(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        int stance = chr.getStance();
        if (stance == WALK_STANCE_RIGHT || stance == WALK_STANCE_LEFT || stance == CLIMB_STANCE) {
            chr.broadcastStance(chr.isFacingLeft() ? STAND_STANCE_LEFT : STAND_STANCE_RIGHT);
        }
    }

    /**
     * An open, non-scripted portal on the agent's current map that leads directly to
     * {@code targetMapId}. Scripted portals are skipped because entering them runs arbitrary
     * script logic rather than a plain map transition.
     */
    static Portal findPortalTo(Character chr, int targetMapId) {
        if (chr == null || chr.getMap() == null || targetMapId <= 0) {
            return null;
        }
        for (Portal portal : chr.getMap().getPortals()) {
            if (portal.getType() != Portal.DOOR_PORTAL
                    && portal.getTargetMapId() == targetMapId
                    && portal.getPortalStatus()
                    && portal.getScriptName() == null) {
                return portal;
            }
        }
        return null;
    }

    static boolean hasPortalTo(Character chr, int targetMapId) {
        return findPortalTo(chr, targetMapId) != null;
    }

    /**
     * Walks to a portal that leads to {@code targetMapId} and enters it once adjacent, then
     * clears the transition flag (agents never send the client's PLAYER_MAP_TRANSFER packet).
     *
     * @return whether the agent ended up on the target map
     */
    static boolean walkThroughPortalTo(AgentSession session, int targetMapId) {
        Character chr = session.getPlayer();
        if (!canSelfTransfer(chr)) {
            return false;
        }
        Portal portal = findPortalTo(chr, targetMapId);
        if (portal == null || portal.getPosition() == null) {
            return false;
        }
        Point self = chr.getPosition();
        if (self == null || self.distanceSq(portal.getPosition()) > PORTAL_ENTER_RANGE_SQ) {
            stepToward(session, self, portal.getPosition(), MAX_STEP);
            return false;
        }
        portal.enterPortal(chr.getClient());
        chr.setMapTransitionComplete();
        return chr.getMapId() == targetMapId;
    }

    static Point clampToMap(Character chr, int x, int y) {
        int cx = clampToShort(x);
        int cy = clampToShort(y);
        Rectangle area = chr.getMap() == null ? null : chr.getMap().getMapArea();
        if (area != null && area.width > 0 && area.height > 0) {
            cx = Math.max(area.x, Math.min(area.x + area.width, cx));
            cy = Math.max(area.y, Math.min(area.y + area.height, cy));
        }
        return new Point(cx, cy);
    }

    private static int clampToShort(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /**
     * Whether the agent may warp to {@code target} on another map to keep following it. Mirrors
     * the same-member behaviour of {@code AbstractPlayerInteraction#warpParty} but refuses
     * anything that could corrupt state: dead/changing agents, trades, cash shops, event
     * instances, and cross-channel targets (which need a channel transfer, not a map warp).
     */
    /**
     * Self-transfer safety guards shared by portal walking: the agent must be alive, on a map,
     * not mid-transition, not in an event/trade/cash shop. Mirrors the agent-side half of
     * {@link #canWarpTo(Character, Character)}.
     */
    static boolean canSelfTransfer(Character agent) {
        return agent != null && agent.isAlive() && agent.getMap() != null && !agent.isChangingMaps()
                && agent.getClient() != null && agent.getEventInstance() == null
                && agent.getTrade() == null
                && (agent.getCashShop() == null || !agent.getCashShop().isOpened());
    }

    static boolean canWarpTo(Character agent, Character target) {
        if (agent == null || target == null || agent.getClient() == null || target.getClient() == null) {
            return false;
        }
        if (!agent.isAlive() || agent.isChangingMaps() || agent.getMap() == null || target.getMap() == null) {
            return false;
        }
        if (agent.getMapId() == target.getMapId()) {
            return false; // same map: walk instead of warping
        }
        if (agent.getEventInstance() != null || target.getEventInstance() != null) {
            return false; // never yank an agent into (or out of) an event/instance map
        }
        if (agent.getTrade() != null || (agent.getCashShop() != null && agent.getCashShop().isOpened())) {
            return false;
        }
        if (!target.isLoggedinWorld() || (target.getCashShop() != null && target.getCashShop().isOpened())) {
            return false;
        }
        if (agent.getClient().getChannel() != target.getClient().getChannel()) {
            return false; // cross-channel follow is not supported here
        }
        return agent.getClient().getChannelServer() != null;
    }

    /**
     * Warps {@code agent} to {@code target}'s map if {@link #canWarpTo} allows it, then clears
     * the transition flag that {@code changeMapInternal} sets (agents never send the client's
     * PLAYER_MAP_TRANSFER packet that normally clears it).
     *
     * @return whether a warp happened
     */
    static boolean warpTo(Character agent, Character target) {
        if (!canWarpTo(agent, target)) {
            return false;
        }
        agent.changeMap(target.getMapId());
        agent.setMapTransitionComplete();
        return true;
    }

    /** Builds a single-command absolute movement packet. Package-private for testing. */
    static InPacket buildMovePacket(int x, int y, int stance) {
        return buildMovePacket(x, y, stance, x, y);
    }

    /**
     * Builds a single-command absolute movement packet. The fragment duration is the time to cover
     * the step at {@link #WALK_SPEED_PX_PER_SEC} (capped at one tick), and the velocity
     * ({@code xwobble}/{@code ywobble}, pixels/second) matches it, so the client walks at a normal
     * pace instead of dashing the whole step in a fixed 120 ms.
     */
    static InPacket buildMovePacket(int x, int y, int stance, int fromX, int fromY) {
        int dx = x - fromX;
        int dy = y - fromY;
        double distance = Math.hypot(dx, dy);
        int maxDuration = (int) Math.max(1L, Math.min(AgentConfig.tickMs(), 4000L));
        int duration = (int) Math.max(1L, Math.min(maxDuration, Math.round(distance / WALK_SPEED_PX_PER_SEC * 1000.0)));
        int vx = (int) Math.round(dx * 1000.0 / duration);
        int vy = (int) Math.round(dy * 1000.0 / duration);
        OutPacket out = new ByteBufOutPacket();
        out.skip(9); // timestamp/header bytes; MovePlayerHandler skips these and never echoes them
        out.writeByte(1); // a single movement command
        out.writeByte(0); // 0 = normal/absolute movement
        out.writeShort(x);
        out.writeShort(y);
        out.writeShort(vx); // xwobble (pixels/second)
        out.writeShort(vy); // ywobble (pixels/second)
        out.writeShort(0); // foothold (ignored by updatePosition for absolute moves)
        out.writeByte(stance);
        out.writeShort(duration);

        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    /** Largest step (per axis) that keeps a follow walk at a normal pace across one tick. */
    public static int walkStepPerTick() {
        return (int) Math.max(1L, Math.min(400L,
                Math.round(WALK_SPEED_PX_PER_SEC * AgentConfig.tickMs() / 1000.0)));
    }
}
