package net.server.agent.harness;

import net.packet.Packet;
import net.server.agent.AgentSession;
import net.opcodes.SendOpcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import constants.string.CharsetConstants;

/**
 * Decodes the slice of outbound packets that matters for harness NPC-dialog perception.
 * <p>
 * Agent clients normally drop outbound packets; harness sessions instead decode
 * {@code NPC_TALK} frames (the dialog packets NPC scripts emit through
 * {@code PacketCreator#getNPCTalk*}) into structured state on the session, so the testing
 * agent can see what the NPC said and which response kind is pending.
 * <p>
 * Frame layout (v83): {@code [opcode:2][?:1][npcId:4][msgType:1][speaker:1][talk string][tail]}.
 * The string is the same length-prefixed encoding {@code OutPacket#writeString} writes.
 */
public final class HarnessDialogDecoder {
    private static final Logger log = LoggerFactory.getLogger(HarnessDialogDecoder.class);

    /** v83 msg types seen in PacketCreator dialog packets (subset the harness understands). */
    private static final String[] TYPE_NAMES = {
            "say", "yes_no", "text_input", "number_input", "selection", "unknown5",
            "unknown6", "style", "quiz", "speed_quiz", "unknown10", "unknown11",
            "accept_decline", "unknown13", "dimensional_mirror"
    };

    private HarnessDialogDecoder() {
    }

    /** Attempts to decode {@code packet}; returns true if it updated the session's dialog state. */
    public static boolean process(AgentSession session, Packet packet) {
        byte[] bytes = packet.getBytes();
        if (bytes == null || bytes.length < 10) {
            // 7-byte enable-actions frame (STAT_CHANGED + true + empty mask) = the script called
            // dispose(). Mark the context closed but KEEP the last frame text visible — quiz and
            // confirm frames are often followed by an immediate dispose in the same script tick.
            if (bytes != null && bytes.length == 7
                    && (bytes[0] & 0xFF | (bytes[1] & 0xFF) << 8) == SendOpcode.STAT_CHANGED.getValue()
                    && bytes[2] == 1
                    && (bytes[3] | bytes[4] << 8 | bytes[5] << 16 | bytes[6] << 24) == 0) {
                session.setNpcDialogClosed();
                return true;
            }
            return false;
        }
        final int opcode = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        if (opcode == SendOpcode.STORAGE.getValue()) {
            // getStorage frame: [opcode:2][0x16][npcId:4]... - the account-storage UI just opened.
            if (bytes.length >= 7) {
                session.setStorageOpened(true);
                return true;
            }
            return false;
        }
        if (opcode != SendOpcode.NPC_TALK.getValue()) {
            return false;
        }
        try {
            int npcId = (bytes[3] & 0xFF) | ((bytes[4] & 0xFF) << 8)
                    | ((bytes[5] & 0xFF) << 16) | ((bytes[6] & 0xFF) << 24);
            int msgType = bytes[7] & 0xFF;
            int cursor = 9; // skip the speaker byte
            String talk = "";
            if (msgType != 0x6 && msgType != 0x7) { // quiz frames have their own tail layout
                int len = ((bytes[cursor] & 0xFF) | ((bytes[cursor + 1] & 0xFF) << 8));
                if (len > 0 && cursor + 2 + len <= bytes.length) {
                    talk = new String(bytes, cursor + 2, len, CharsetConstants.CHARSET);
                }
            }
            session.setNpcDialog(npcId, msgType, talk);
            return true;
        } catch (Exception e) {
            log.debug("Failed to decode NPC dialog frame ({} bytes)", bytes.length, e);
            return false;
        }
    }

    /** Human-readable name for a msg type, for observations. */
    public static String typeName(int msgType) {
        return msgType >= 0 && msgType < TYPE_NAMES.length ? TYPE_NAMES[msgType] : "unknown" + msgType;
    }
}
