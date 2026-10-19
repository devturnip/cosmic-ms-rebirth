package net.server.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.server.agent.AgentAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing tests for the PlayerInteraction room vocabulary (minigames / hired merchants),
 * mirroring {@link HarnessActionsTest}.
 */
class HarnessActionsInteractionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesMinigameRoomActions() throws Exception {
        String json = """
                [
                  {"type":"MiniGameCreate","gameType":"omok","description":"my board","password":"pw","pieceType":2},
                  {"type":"MiniGameCreate","gameType":"matchcard","pieceType":1},
                  {"type":"RoomVisit","ownerName":"Agent-A"},
                  {"type":"MiniGameStart"},
                  {"type":"MiniGameMoveOmok","x":3,"y":4,"piece":1},
                  {"type":"MiniGameSelectCard","turn":1,"slot":7},
                  {"type":"MiniGameGiveUp"},
                  {"type":"MiniGameTieRequest"},
                  {"type":"MiniGameTieAnswer","accept":true},
                  {"type":"RoomExit"}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(10, actions.size());
        AgentAction.MiniGameCreate create = assertInstanceOf(AgentAction.MiniGameCreate.class, actions.get(0));
        assertEquals("omok", create.gameType());
        assertEquals("my board", create.description());
        assertEquals("pw", create.password());
        assertEquals(2, create.pieceType());
        assertEquals(1, assertInstanceOf(AgentAction.MiniGameCreate.class, actions.get(1)).pieceType());
        assertEquals("Agent-A", assertInstanceOf(AgentAction.RoomVisit.class, actions.get(2)).ownerName());
        assertInstanceOf(AgentAction.MiniGameStart.class, actions.get(3));
        AgentAction.MiniGameMoveOmok move = assertInstanceOf(AgentAction.MiniGameMoveOmok.class, actions.get(4));
        assertEquals(3, move.x());
        assertEquals(4, move.y());
        assertEquals(1, move.piece());
        AgentAction.MiniGameSelectCard select = assertInstanceOf(AgentAction.MiniGameSelectCard.class, actions.get(5));
        assertEquals(1, select.turn());
        assertEquals(7, select.slot());
        assertInstanceOf(AgentAction.MiniGameGiveUp.class, actions.get(6));
        assertInstanceOf(AgentAction.MiniGameTieRequest.class, actions.get(7));
        assertTrue(assertInstanceOf(AgentAction.MiniGameTieAnswer.class, actions.get(8)).accept());
        assertInstanceOf(AgentAction.RoomExit.class, actions.get(9));
    }

    @Test
    void parsesHiredMerchantActions() throws Exception {
        String json = """
                [
                  {"type":"HiredMerchantCreate","description":"cheep potions","itemId":5030012},
                  {"type":"HiredMerchantPutItem","inventoryType":"USE","slot":5,"bundles":10,"perBundle":100,"price":500},
                  {"type":"HiredMerchantOpen"},
                  {"type":"HiredMerchantBuy","itemIndex":0,"quantity":2},
                  {"type":"HiredMerchantWithdrawMesos"},
                  {"type":"HiredMerchantTakeItemBack","slot":0},
                  {"type":"HiredMerchantClose"}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(7, actions.size());
        AgentAction.HiredMerchantCreate create = assertInstanceOf(AgentAction.HiredMerchantCreate.class, actions.get(0));
        assertEquals("cheep potions", create.description());
        assertEquals(5030012, create.itemId());
        AgentAction.HiredMerchantPutItem put = assertInstanceOf(AgentAction.HiredMerchantPutItem.class, actions.get(1));
        assertEquals("USE", put.inventoryType());
        assertEquals(5, put.slot());
        assertEquals(10, put.bundles());
        assertEquals(100, put.perBundle());
        assertEquals(500, put.price());
        assertInstanceOf(AgentAction.HiredMerchantOpen.class, actions.get(2));
        AgentAction.HiredMerchantBuy buy = assertInstanceOf(AgentAction.HiredMerchantBuy.class, actions.get(3));
        assertEquals(0, buy.itemIndex());
        assertEquals(2, buy.quantity());
        assertInstanceOf(AgentAction.HiredMerchantWithdrawMesos.class, actions.get(4));
        assertEquals(0, assertInstanceOf(AgentAction.HiredMerchantTakeItemBack.class, actions.get(5)).slot());
        assertInstanceOf(AgentAction.HiredMerchantClose.class, actions.get(6));
    }

    @Test
    void minigameCreateDefaultsAreOptional() throws Exception {
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(
                """
                [{"type":"MiniGameCreate","gameType":"omok","pieceType":0}]
                """));
        AgentAction.MiniGameCreate create = assertInstanceOf(AgentAction.MiniGameCreate.class, actions.get(0));
        assertNull(create.password());
    }

    @Test
    void rejectsMissingRequiredRoomFields() {
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(
                """
                [{"type":"HiredMerchantCreate","description":"x"}]
                """)));
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(
                """
                [{"type":"MiniGameMoveOmok","x":1,"y":2}]
                """)));
    }
}
