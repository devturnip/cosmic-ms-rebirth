package net.server.agent;

import client.Character;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;

import java.awt.Point;

/**
 * Helpers for moving an agent around its map. Movement is performed by synthesizing a
 * {@code MOVE_PLAYER} packet (a single absolute movement command) and dispatching it through
 * {@code MovePlayerHandler}, so the normal position/stance update and map broadcast happen.
 */
public final class AgentMovement {
    private static final int WALK_STANCE_RIGHT = 0;
    private static final int WALK_STANCE_LEFT = 1;
    private static final short DEFAULT_DURATION = 120;

    private AgentMovement() {
    }

    /** Moves the agent one step toward {@code targetX} (keeping {@code targetY}). */
    public static void stepToward(AgentSession session, Point current, Point target, int maxStep) {
        if (current == null || target == null) {
            return;
        }
        int dx = target.x - current.x;
        int dy = target.y - current.y;
        if (Math.abs(dx) <= 20) {
            return; // close enough horizontally
        }
        int step = Integer.signum(dx) * Math.min(Math.abs(dx), Math.max(1, maxStep));
        moveTo(session, current.x + step, target.y != current.y ? target.y : current.y);
    }

    /** Sends an absolute move to the given position. */
    public static void moveTo(AgentSession session, int x, int y) {
        Character chr = session.getPlayer();
        Point current = chr == null ? null : chr.getPosition();
        int stance = current != null && x < current.x ? WALK_STANCE_LEFT : WALK_STANCE_RIGHT;
        session.dispatchInbound(RecvOpcode.MOVE_PLAYER, buildMovePacket(x, y, stance));
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
