package net.server.agent;

import client.Character;
import io.netty.buffer.Unpooled;
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

    private AgentMovement() {
    }

    /** Moves the agent one step toward {@code target} (keeping the target's height). */
    public static void stepToward(AgentSession session, Point current, Point target, int maxStep) {
        if (current == null || target == null) {
            return;
        }
        int dx = target.x - current.x;
        if (Math.abs(dx) <= 20) {
            return; // close enough horizontally
        }
        int step = Integer.signum(dx) * Math.min(Math.abs(dx), Math.max(1, maxStep));
        moveTo(session, current.x + step, target.y);
    }

    /** Sends a clamped, single-step absolute move toward the given position. */
    public static void moveTo(AgentSession session, int x, int y) {
        Character chr = session.getPlayer();
        if (chr == null) {
            return;
        }
        Point current = chr.getPosition();
        int targetX = x;
        if (current != null) {
            int dx = x - current.x;
            if (Math.abs(dx) > MAX_STEP) {
                targetX = current.x + Integer.signum(dx) * MAX_STEP;
            }
        }
        Point clamped = clampToMap(chr, targetX, y);
        int stance = current != null && clamped.x < current.x ? WALK_STANCE_LEFT : WALK_STANCE_RIGHT;
        session.dispatchInbound(RecvOpcode.MOVE_PLAYER, buildMovePacket(clamped.x, clamped.y, stance));
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
