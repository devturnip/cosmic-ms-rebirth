package net.server.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.server.agent.AgentAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parsing tests for the world-interaction vocabulary (reactor hits, catch items, drops),
 * mirroring {@link HarnessActionsTest}.
 */
class HarnessActionsWorldTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesWorldInteractionActions() throws Exception {
        String json = """
                [
                  {"type":"ReactorHit","reactorObjectId":7},
                  {"type":"ReactorHit","objectId":9},
                  {"type":"CatchItem","mobObjectId":42,"itemId":2270004},
                  {"type":"DropItem","inventoryType":"ETC","slot":9,"quantity":4}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(4, actions.size());
        assertEquals(7, assertInstanceOf(AgentAction.ReactorHit.class, actions.get(0)).reactorObjectId());
        assertEquals(9, assertInstanceOf(AgentAction.ReactorHit.class, actions.get(1)).reactorObjectId(),
                "'objectId' is accepted as an alias for 'reactorObjectId'");
        AgentAction.CatchItem catchItem = assertInstanceOf(AgentAction.CatchItem.class, actions.get(2));
        assertEquals(42, catchItem.mobObjectId());
        assertEquals(2270004, catchItem.itemId());
        AgentAction.DropItem drop = assertInstanceOf(AgentAction.DropItem.class, actions.get(3));
        assertEquals("ETC", drop.inventoryType());
        assertEquals(9, drop.slot());
        assertEquals(4, drop.quantity());
    }

    @Test
    void reactorHitWithoutAnyObjectIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                HarnessActions.parse(mapper.readTree("[{\"type\":\"ReactorHit\"}]")));
    }

    @Test
    void catchItemRequiresMobAndItem() {
        assertThrows(IllegalArgumentException.class, () ->
                HarnessActions.parse(mapper.readTree("[{\"type\":\"CatchItem\",\"mobObjectId\":1}]")));
        assertThrows(IllegalArgumentException.class, () ->
                HarnessActions.parse(mapper.readTree("[{\"type\":\"CatchItem\",\"itemId\":2270004}]")));
    }
}
