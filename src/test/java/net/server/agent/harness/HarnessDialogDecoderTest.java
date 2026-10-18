package net.server.agent.harness;

import net.packet.Packet;
import net.server.agent.AgentSession;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level tests for the outbound NPC-dialog decoder. {@code PacketCreator} builds the
 * exact frames a real NPC script would send; the decoder must recover npcId, msgType and text.
 */
class HarnessDialogDecoderTest {

    @Test
    void decodesSayDialog() {
        Packet packet = PacketCreator.getNPCTalk(2101, (byte) 0, "Hey there, want to train?",
                "00", (byte) 0);
        AgentSession session = new SessionStub();
        assertTrue(HarnessDialogDecoder.process(session, packet));
        assertEquals(2101, session.getNpcDialogNpcId());
        assertEquals(0, session.getNpcDialogMsgType());
        assertTrue(session.getNpcDialogText().contains("train?"));
        assertEquals("say", HarnessDialogDecoder.typeName(0));
    }

    @Test
    void decodesYesNoDialog() {
        Packet packet = PacketCreator.getNPCTalk(2100, (byte) 1, "Accept the quest?", "00", (byte) 0);
        AgentSession session = new SessionStub();
        assertTrue(HarnessDialogDecoder.process(session, packet));
        assertEquals(2100, session.getNpcDialogNpcId());
        assertEquals(1, session.getNpcDialogMsgType());
        assertEquals("yes_no", HarnessDialogDecoder.typeName(1));
    }

    @Test
    void decodesSelectionAndNumberDialogs() {
        AgentSession session = new SessionStub();
        assertTrue(HarnessDialogDecoder.process(session,
                PacketCreator.getNPCTalkNum(9200000, "How many?", 5, 1, 10)));
        assertEquals(3, session.getNpcDialogMsgType());
        assertTrue(HarnessDialogDecoder.process(session,
                PacketCreator.getNPCTalkText(9200000, "Your name?", "SpongeBob")));
        assertEquals(2, session.getNpcDialogMsgType());
        assertEquals("Your name?", session.getNpcDialogText());
    }

    @Test
    void ignoresNonDialogPackets() {
        AgentSession session = new SessionStub();
        assertFalse(HarnessDialogDecoder.process(session, () -> new byte[]{1, 2, 3}));
        assertFalse(HarnessDialogDecoder.process(session, () -> new byte[0]));
        assertEquals(-1, session.getNpcDialogMsgType());
    }

    /** Minimal concrete AgentSession: the dialog state lives on the session itself. */
    private static final class SessionStub extends AgentSession {
        // Netty-free constructor is not available; AgentSession needs a client — but dialog
        // state methods don't touch it, so pass nulls via the harness constructor.
        SessionStub() {
            super(null, null, new HarnessBrain(), new net.server.agent.AgentMemory(1), true, -1, true);
        }
    }
}
