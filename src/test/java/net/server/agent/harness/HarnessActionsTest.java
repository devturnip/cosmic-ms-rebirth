package net.server.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.server.agent.AgentAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessActionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesChatAndMovement() throws Exception {
        String json = """
                [
                  {"type":"Say","message":"hello world"},
                  {"type":"MoveTo","x":-120,"y":30},
                  {"type":"Idle"}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(3, actions.size());
        assertInstanceOf(AgentAction.Say.class, actions.get(0));
        assertEquals("hello world", ((AgentAction.Say) actions.get(0)).message());
        assertInstanceOf(AgentAction.MoveTo.class, actions.get(1));
        assertEquals(-120, ((AgentAction.MoveTo) actions.get(1)).x());
        assertInstanceOf(AgentAction.Idle.class, actions.get(2));
    }

    @Test
    void parsesCombatAndInventory() throws Exception {
        String json = """
                [
                  {"type":"Attack","targetObjectId":12345},
                  {"type":"SkillAttack","targetObjectId":1,"skillId":1001003},
                  {"type":"UseItem","itemId":2000000},
                  {"type":"PickupItem","objectId":42},
                  {"type":"SkillAttackMany","targetObjectIds":[1,2,3],"skillId":10}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(5, actions.size());
        assertInstanceOf(AgentAction.SkillAttackMany.class, actions.get(4));
        assertEquals(List.of(1, 2, 3), ((AgentAction.SkillAttackMany) actions.get(4)).targetObjectIds());
    }

    @Test
    void rejectsUnknownType() throws Exception {
        String json = """
                [{"type":"TeleportHome"}]
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HarnessActions.parse(mapper.readTree(json)));
        assertTrue(e.getMessage().contains("TeleportHome"));
    }

    @Test
    void rejectsMissingIntegerFields() throws Exception {
        String json = """
                [{"type":"MoveTo","y":3}]
                """;
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(json)));
    }

    @Test
    void rejectsNonArrayPayload() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> HarnessActions.parse(mapper.readTree("{\"type\":\"Say\"}")));
    }
}
