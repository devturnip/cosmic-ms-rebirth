package net.server.agent;

import client.Character;
import net.packet.InPacket;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import server.maps.MiniGame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packet-shape tests for the PlayerInteraction room actions: each action must synthesize the
 * exact inbound bytes {@link net.server.channel.handlers.PlayerInteractionHandler} parses.
 */
class AgentActionDispatcherInteractionPacketTest {
    private final AgentActionDispatcher dispatcher = new AgentActionDispatcher();
    private AgentSession session;

    @BeforeEach
    void setUp() {
        session = Mockito.mock(AgentSession.class);
        Mockito.when(session.allowAction()).thenReturn(true);
    }

    private InPacket dispatchedPacket() {
        ArgumentCaptor<InPacket> captor = ArgumentCaptor.forClass(InPacket.class);
        Mockito.verify(session).dispatchInbound(Mockito.eq(RecvOpcode.PLAYER_INTERACTION), captor.capture());
        return captor.getValue();
    }

    @Test
    void miniGameCreateEncodesCreateOmokWithDescriptionAndPassword() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameCreate("omok", "my board", "pw", 2)));

        InPacket p = dispatchedPacket();
        assertEquals(0, p.readByte(), "CREATE mode");
        assertEquals(1, p.readByte(), "omok room type");
        assertEquals("my board", p.readString());
        assertEquals(1, p.readByte(), "has-password flag");
        assertEquals("pw", p.readString());
        assertEquals(2, p.readByte(), "piece type");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void miniGameCreateEncodesMatchCardWithoutPassword() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameCreate("MatchCard", null, null, 1)));

        InPacket p = dispatchedPacket();
        assertEquals(0, p.readByte(), "CREATE mode");
        assertEquals(2, p.readByte(), "match card room type");
        assertEquals("", p.readString());
        assertEquals(0, p.readByte(), "no password");
        assertEquals(1, p.readByte(), "piece type");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void roomExitIsTheGenericExitPacket() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.RoomExit()));

        InPacket p = dispatchedPacket();
        assertEquals(0x0A, p.readByte(), "EXIT mode");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void moveOmokEncodesCoordinatesAndPiece() {
        Character chr = Mockito.mock(Character.class);
        MiniGame game = new MiniGame(chr, "test", "");
        Mockito.when(session.getPlayer()).thenReturn(chr);
        Mockito.when(chr.getMiniGame()).thenReturn(game);

        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameMoveOmok(3, 8, 2)));

        InPacket p = dispatchedPacket();
        assertEquals(0x40, p.readByte(), "MOVE_OMOK mode");
        assertEquals(3, p.readInt(), "x");
        assertEquals(8, p.readInt(), "y");
        assertEquals(2, p.readByte(), "piece");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void selectCardEncodesTurnAndSlot() {
        Character chr = Mockito.mock(Character.class);
        MiniGame game = new MiniGame(chr, "test", "");
        Mockito.when(session.getPlayer()).thenReturn(chr);
        Mockito.when(chr.getMiniGame()).thenReturn(game);

        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameSelectCard(1, 7)));

        InPacket p = dispatchedPacket();
        assertEquals(0x44, p.readByte(), "SELECT_CARD mode");
        assertEquals(1, p.readByte(), "turn");
        assertEquals(7, p.readByte(), "slot");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void minigameActionsWithoutARoomAreNotDispatched() {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(session.getPlayer()).thenReturn(chr); // getMiniGame() defaults to null

        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameStart()));
        Mockito.verify(session, Mockito.never()).dispatchInbound(Mockito.any(), Mockito.any());
    }

    @Test
    void hiredMerchantCreateEncodesShopRoomWithItemId() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantCreate("cheep potions", 5030012)));

        InPacket p = dispatchedPacket();
        assertEquals(0, p.readByte(), "CREATE mode");
        assertEquals(5, p.readByte(), "hired merchant shop type");
        assertEquals("cheep potions", p.readString());
        p.skip(3); // password block the handler skips
        assertEquals(5030012, p.readInt(), "merchant item id");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void hiredMerchantPutItemEncodesListing() {
        assertTrue(dispatcher.dispatch(session,
                new AgentAction.HiredMerchantPutItem("USE", (short) 5, (short) 10, (short) 100, 500)));

        InPacket p = dispatchedPacket();
        assertEquals(0x21, p.readByte(), "PUT_ITEM mode");
        assertEquals(2, p.readByte(), "USE inventory type");
        assertEquals(5, p.readShort(), "slot");
        assertEquals(10, p.readShort(), "bundles");
        assertEquals(100, p.readShort(), "per bundle");
        assertEquals(500, p.readInt(), "price");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void hiredMerchantBuyEncodesIndexAndQuantity() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantBuy(2, (short) 3)));

        InPacket p = dispatchedPacket();
        assertEquals(0x22, p.readByte(), "MERCHANT_BUY mode");
        assertEquals(2, p.readByte(), "item index");
        assertEquals(3, p.readShort(), "quantity");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void merchantMaintenancePacketsAreSingleModeByte() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantWithdrawMesos()));
        assertEquals(0x2B, dispatchedPacket().readByte(), "MERCHANT_MESO mode");

        Mockito.clearInvocations(session);
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantTakeItemBack(4)));
        InPacket take = dispatchedPacket();
        assertEquals(0x26, take.readByte(), "TAKE_ITEM_BACK mode");
        assertEquals(4, take.readShort(), "slot");
        assertEquals(0, take.available(), "packet fully consumed");

        Mockito.clearInvocations(session);
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantClose()));
        assertEquals(0x29, dispatchedPacket().readByte(), "CLOSE_MERCHANT mode");

        Mockito.clearInvocations(session);
        assertTrue(dispatcher.dispatch(session, new AgentAction.HiredMerchantOpen()));
        InPacket open = dispatchedPacket();
        assertEquals(0x0B, open.readByte(), "OPEN_STORE mode");
        assertEquals(1, open.readByte(), "store sub-op");
        assertEquals(0, open.available(), "packet fully consumed");
    }

    @Test
    void tieAnswerEncodesTheDecision() {
        Character chr = Mockito.mock(Character.class);
        MiniGame game = new MiniGame(chr, "test", "");
        Mockito.when(session.getPlayer()).thenReturn(chr);
        Mockito.when(chr.getMiniGame()).thenReturn(game);

        assertTrue(dispatcher.dispatch(session, new AgentAction.MiniGameTieAnswer(true)));

        InPacket p = dispatchedPacket();
        assertNotNull(p);
        assertEquals(0x33, p.readByte(), "ANSWER_TIE mode");
        assertEquals(1, p.readByte(), "accept");
        assertEquals(0, p.available(), "packet fully consumed");
    }
}
