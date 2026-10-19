package net.server.agent.harness;

import client.Character;
import client.Client;
import net.server.agent.AgentAction;
import net.server.agent.AgentSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.MiniGame;
import server.maps.PlayerShopItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance-verification tests for the PlayerInteraction room actions: {@code capture} /
 * {@code verify} must report whether the server state actually changed, using a real
 * {@link MiniGame} / {@link HiredMerchant} and mocked characters (mirroring how the handlers
 * mutate them).
 */
class HarnessInteractionVerifierTest {
    private final AgentSession session = Mockito.mock(AgentSession.class);

    private Character player;
    private MiniGame game;

    /** A mocked character that passes the harness's null-map guards and the merchant constructor. */
    private Character mockCharacter(int id) {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(id);
        Mockito.when(chr.getPosition()).thenReturn(new Point(1, 1));
        Mockito.when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        Mockito.when(chr.getClient()).thenReturn(Mockito.mock(Client.class));
        Mockito.when(session.getPlayer()).thenReturn(chr);
        return chr;
    }

    /** Simulates {@code MiniGame#addVisitor} without the appearance-packet side effects. */
    private void attachVisitor(MiniGame target, Character visitor) throws Exception {
        java.lang.reflect.Field field = MiniGame.class.getDeclaredField("visitor");
        field.setAccessible(true);
        field.set(target, visitor);
    }

    private void setUpOmokOwner() {
        player = mockCharacter(1);
        game = new MiniGame(player, "test", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        Mockito.when(player.getMiniGame()).thenReturn(game);
    }

    @Test
    void miniGameCreateVerifiesWhenARoomAppears() {
        Character chr = mockCharacter(7);
        AgentAction.MiniGameCreate action = new AgentAction.MiniGameCreate("omok", "d", null, 0);

        Object pre = HarnessVerifier.capture(session, action);
        assertFalse((Boolean) pre, "no room pre-create");
        Mockito.when(chr.getMiniGame()).thenReturn(new MiniGame(chr, "d", ""));

        assertTrue(HarnessVerifier.verify(session, action, pre));
        assertFalse(HarnessVerifier.verify(session, action, Boolean.TRUE));
    }

    @Test
    void omokMoveVerifiesViaBoardState() {
        setUpOmokOwner();
        AgentAction.MiniGameMoveOmok action = new AgentAction.MiniGameMoveOmok(3, 4, 1);

        Object pre = HarnessVerifier.capture(session, action);
        game.setPiece(3, 4, 1, player); // what the handler does with an accepted move
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void omokMoveOntoOccupiedSquareIsRefused() {
        setUpOmokOwner();
        AgentAction.MiniGameMoveOmok action = new AgentAction.MiniGameMoveOmok(3, 4, 1);
        game.setPiece(3, 4, 1, player);

        Object pre = HarnessVerifier.capture(session, action);
        assertFalse(HarnessVerifier.verify(session, action, pre), "square already occupied");
    }

    @Test
    void giveUpVerifiesViaScoreChange() throws Exception {
        setUpOmokOwner();
        attachVisitor(game, Mockito.mock(Character.class)); // a forfeit needs a real opponent
        game.minigameMatchStarted(); // a match must be in progress to forfeit
        AgentAction.MiniGameGiveUp action = new AgentAction.MiniGameGiveUp();

        Object pre = HarnessVerifier.capture(session, action);
        game.minigameMatchVisitorWins(true); // owner forfeits: visitor wins

        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void giveUpWithoutMatchInProgressIsRefused() {
        setUpOmokOwner();
        AgentAction.MiniGameGiveUp action = new AgentAction.MiniGameGiveUp();

        Object pre = HarnessVerifier.capture(session, action);
        assertFalse(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void selectCardFirstFlipVerifiesViaFirstSlot() {
        setUpOmokOwner();
        game.setGameType(MiniGame.MiniGameType.MATCH_CARD);
        game.setMatchesToWin(6);
        AgentAction.MiniGameSelectCard action = new AgentAction.MiniGameSelectCard(1, 7);

        Object pre = HarnessVerifier.capture(session, action);
        game.setFirstSlot(7); // what the handler does for turn == 1
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void nonScoringSecondFlipIsNotObservable() {
        setUpOmokOwner();
        game.setGameType(MiniGame.MiniGameType.MATCH_CARD);
        game.setMatchesToWin(6);
        game.setFirstSlot(7);
        AgentAction.MiniGameSelectCard action = new AgentAction.MiniGameSelectCard(0, 3);

        Object pre = HarnessVerifier.capture(session, action);
        assertNull(HarnessVerifier.verify(session, action, pre),
                "a non-matching second flip only broadcasts; acceptance is unknown");
    }

    @Test
    void roomVisitAndExitVerifyViaRoomMembership() {
        Character chr = mockCharacter(3);
        AgentAction.RoomVisit visit = new AgentAction.RoomVisit("owner", null);

        Object pre = HarnessVerifier.capture(session, visit);
        assertFalse((Boolean) pre);
        MiniGame joined = new MiniGame(Mockito.mock(Character.class), "d", "");
        Mockito.when(chr.getMiniGame()).thenReturn(joined);
        assertTrue(HarnessVerifier.verify(session, visit, pre));

        AgentAction.RoomExit exit = new AgentAction.RoomExit();
        Object preExit = HarnessVerifier.capture(session, exit);
        assertTrue((Boolean) preExit);
        Mockito.when(chr.getMiniGame()).thenReturn(null);
        Mockito.when(chr.getHiredMerchant()).thenReturn(null);
        assertTrue(HarnessVerifier.verify(session, exit, preExit));
    }

    @Test
    void hiredMerchantCreateVerifiesViaMaintenanceReference() {
        Character owner = mockCharacter(42);
        HiredMerchant merchant = new HiredMerchant(owner, "shop", 5030012);
        AgentAction.HiredMerchantCreate action = new AgentAction.HiredMerchantCreate("shop", 5030012);

        Mockito.when(owner.getHiredMerchant()).thenReturn(null, merchant);
        Object pre = HarnessVerifier.capture(session, action);
        assertFalse((Boolean) pre);
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void hiredMerchantPutItemVerifiesViaListingChange() {
        Character owner = mockCharacter(42);
        HiredMerchant merchant = new HiredMerchant(owner, "shop", 5030012);
        Mockito.when(owner.getHiredMerchant()).thenReturn(merchant);
        AgentAction.HiredMerchantPutItem action =
                new AgentAction.HiredMerchantPutItem("USE", (short) 1, (short) 5, (short) 100, 500);

        Object pre = HarnessVerifier.capture(session, action);
        assertTrue(merchant.addItem(new PlayerShopItem(new client.inventory.Item(2000000, (short) 0, (short) 100), (short) 5, 500)));
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void hiredMerchantOpenVerifiesViaMapBoxState() {
        Character owner = mockCharacter(42);
        HiredMerchant merchant = new HiredMerchant(owner, "shop", 5030012);
        Mockito.when(owner.getHiredMerchant()).thenReturn(merchant);
        AgentAction.HiredMerchantOpen action = new AgentAction.HiredMerchantOpen();

        Object pre = HarnessVerifier.capture(session, action);
        assertFalse((Boolean) pre, "merchant starts closed");

        // Owner opens the store: the reference is released and the box appears on the map.
        merchant.setOpen(true);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(map.getMapObjects()).thenReturn(List.of(merchant));
        Mockito.when(owner.getHiredMerchant()).thenReturn(null);
        Mockito.when(owner.getMap()).thenReturn(map);

        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void hiredMerchantCloseVerifiesViaBoxRemoval() {
        Character owner = mockCharacter(42);
        HiredMerchant merchant = new HiredMerchant(owner, "shop", 5030012);
        MapleMap map = Mockito.mock(MapleMap.class);
        Mockito.when(owner.getHiredMerchant()).thenReturn(merchant);
        Mockito.when(owner.getMap()).thenReturn(map);
        Mockito.when(map.getMapObjects()).thenReturn(List.of(merchant));
        AgentAction.HiredMerchantClose action = new AgentAction.HiredMerchantClose();

        Object pre = HarnessVerifier.capture(session, action);
        assertTrue((Boolean) pre, "merchant box is on the map pre-close");

        // closeOwnerMerchant releases the owner reference and removes the box from the map.
        Mockito.when(owner.getHiredMerchant()).thenReturn(null);
        Mockito.when(map.getMapObjects()).thenReturn(List.of());
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void hiredMerchantBuyVerifiesViaMesoDelta() {
        Character buyer = mockCharacter(5);
        Mockito.when(buyer.getMeso()).thenReturn(1000, 940);
        AgentAction.HiredMerchantBuy action = new AgentAction.HiredMerchantBuy(0, (short) 2);

        Object pre = HarnessVerifier.capture(session, action);
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void actionsWithoutARoomAreNotVerifiableOrRefused() {
        mockCharacter(9);

        assertNull(HarnessVerifier.capture(session, new AgentAction.MiniGameStart()));
        assertNull(HarnessVerifier.capture(session, new AgentAction.MiniGameTieRequest()),
                "tie requests only broadcast; acceptance is not observable");
    }
}
