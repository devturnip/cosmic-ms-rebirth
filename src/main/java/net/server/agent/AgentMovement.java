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
    private static final short DEFAULT_DURATION = 120;
    static final int MAX_STEP = 150;
    /** Vertical gap beyond which two positions are treated as different platforms. */
    static final int PLATFORM_TOLERANCE = 80;
    /** Vertical gap beyond which two positions are treated as different platforms (squared). */
    static final int PLATFORM_TOLERANCE_SQ = PLATFORM_TOLERANCE * PLATFORM_TOLERANCE;
    /** Horizontal distance at or below which the agent is treated as aligned for a drop. */
    private static final int PLATFORM_ALIGN_TOLERANCE = 20;
    /** Distance (squared) within which a portal can be entered (~60px). */
    static final int PORTAL_ENTER_RANGE_SQ = 60 * 60;

    private AgentMovement() {
    }

    /** Moves the agent one foothold-aware step toward {@code target}. */
    public static void stepToward(AgentSession session, Point current, Point target, int maxStep) {
        if (current == null || target == null) {
            return;
        }
        if (Math.abs(target.x - current.x) <= 20 && Math.abs(target.y - current.y) <= 20) {
            return; // close enough
        }
        moveToSmart(session, target, maxStep);
    }

    /**
     * Foothold-aware step. When the target is on roughly the same platform the agent walks
     * horizontally along it; when the target is on another platform the agent first closes the
     * horizontal gap (so it drops near the target) and then steps toward it. When the agent's map
     * exposes footholds, the horizontal approach walks along the agent's <em>current</em> foothold
     * and is clamped to that foothold's own x-range, so the agent never walks off an edge before
     * dropping. All movement is clamped per axis by {@link #MAX_STEP}, so a hallucinated
     * destination can never teleport.
     */
    public static void moveToSmart(AgentSession session, Point target, int maxStep) {
        Character chr = session.getPlayer();
        if (chr == null || target == null) {
            return;
        }
        Point current = chr.getPosition();
        if (current == null) {
            return;
        }
        int dx = target.x - current.x;
        int dy = target.y - current.y;
        if (Math.abs(dx) <= PLATFORM_ALIGN_TOLERANCE && Math.abs(dy) <= PLATFORM_ALIGN_TOLERANCE) {
            return;
        }
        if (stepAlongFootholds(session, chr, current, target, maxStep)) {
            return;
        }
        if (dy * dy > PLATFORM_TOLERANCE_SQ && Math.abs(dx) > PLATFORM_ALIGN_TOLERANCE) {
            // Different platform: align horizontally first, then approach vertically.
            int step = Integer.signum(dx) * Math.min(Math.abs(dx), Math.max(1, maxStep));
            moveTo(session, current.x + step, current.y, maxStep);
            return;
        }
        moveTo(session, target.x, target.y, maxStep);
    }

    /**
     * Walks the agent along the foothold it is standing on toward the target and drops toward the
     * foothold below the target x. Returns {@code false} (letting the caller use the plain
     * per-axis step) whenever foothold data is unavailable, the agent is not on a foothold, or the
     * target is on the same foothold. The walk is clamped to the current foothold's x-range and the
     * drop aims at the landing foothold's surface via {@link Foothold#calculateFooting(int)}.
     */
    private static boolean stepAlongFootholds(AgentSession session, Character chr, Point current, Point target, int maxStep) {
        if (chr.getMap() == null) {
            return false;
        }
        FootholdTree tree = chr.getMap().getFootholds();
        if (tree == null) {
            return false;
        }
        Foothold currentFoothold = footholdAt(tree, current);
        if (currentFoothold == null) {
            return false; // agent is not on a walkable foothold: keep the old behaviour
        }
        Foothold landingFoothold = tree.findBelow(target);
        if (landingFoothold == null || landingFoothold == currentFoothold) {
            return false; // same platform (or unknown landing): the plain step is already correct
        }
        int minX = Math.min(currentFoothold.getX1(), currentFoothold.getX2());
        int maxX = Math.max(currentFoothold.getX1(), currentFoothold.getX2());
        int edgeX = Math.max(minX, Math.min(maxX, target.x));
        if (Math.abs(edgeX - current.x) > PLATFORM_ALIGN_TOLERANCE) {
            // Walk along the current foothold toward the target, never past its edge.
            moveTo(session, edgeX, currentFoothold.calculateFooting(edgeX), maxStep);
            return true;
        }
        // Aligned with the target x on the current foothold: drop toward the landing foothold.
        moveTo(session, target.x, landingFoothold.calculateFooting(target.x), maxStep);
        return true;
    }

    /**
     * The non-wall foothold the agent is standing on at {@code p}, or {@code null}. Uses
     * {@link FootholdTree#findBelow(Point)} first and only enumerates the tree when the agent sits
     * slightly off the surface (e.g. part-way through a sloped step).
     */
    private static Foothold footholdAt(FootholdTree tree, Point p) {
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
            int distSq = gap * gap;
            if (distSq <= PLATFORM_TOLERANCE_SQ && distSq < bestDistSq) {
                best = fh;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    /** Steps directly away from a threat by up to {@code maxStep} pixels (per-axis clamped). */
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
        moveTo(session, targetX, targetY, step);
    }

    /** Sends a clamped, single-step absolute move toward the given position. */
    public static void moveTo(AgentSession session, int x, int y) {
        moveTo(session, x, y, MAX_STEP);
    }

    /** Sends a clamped step toward the given position, moving at most {@code maxStep} per axis. */
    public static void moveTo(AgentSession session, int x, int y, int maxStep) {
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
        int stance = current != null && clamped.x < current.x ? WALK_STANCE_LEFT : WALK_STANCE_RIGHT;
        session.dispatchInbound(RecvOpcode.MOVE_PLAYER, buildMovePacket(clamped.x, clamped.y, stance));
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
        OutPacket out = new ByteBufOutPacket();
        out.skip(9); // timestamp/header bytes; MovePlayerHandler skips these and never echoes them
        out.writeByte(1); // a single movement command
        out.writeByte(0); // 0 = normal/absolute movement
        out.writeShort(x);
        out.writeShort(y);
        out.writeShort(0); // xwobble
        out.writeShort(0); // ywobble
        out.writeShort(0); // foothold (ignored by updatePosition for absolute moves)
        out.writeByte(stance);
        out.writeShort(DEFAULT_DURATION);

        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }
}
