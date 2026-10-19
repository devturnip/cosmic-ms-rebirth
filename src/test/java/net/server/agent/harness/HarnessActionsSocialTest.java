package net.server.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.server.agent.AgentAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing tests for the guild / alliance / marriage / family action vocabulary, mirroring
 * {@link HarnessActionsTest} and {@link HarnessActionsInteractionTest}.
 */
class HarnessActionsSocialTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesGuildActions() throws Exception {
        String json = """
                [
                  {"type":"GuildCreate","name":"Cosmonauts"},
                  {"type":"GuildCreateAnswer","accept":true},
                  {"type":"GuildInvite","targetName":"Agent-B"},
                  {"type":"GuildAcceptInvite","inviterName":"Agent-A"},
                  {"type":"GuildLeave","name":"Agent-B"},
                  {"type":"GuildExpel","targetId":42,"targetName":"Agent-B"},
                  {"type":"GuildChangeRankTitle","ranks":["Master","Junior Master","Member","Officer","Rookie"]},
                  {"type":"GuildChangeRank","targetId":42,"newRank":3},
                  {"type":"GuildNotice","notice":"Guild meeting Friday"}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(9, actions.size());
        assertEquals("Cosmonauts", assertInstanceOf(AgentAction.GuildCreate.class, actions.get(0)).name());
        assertTrue(assertInstanceOf(AgentAction.GuildCreateAnswer.class, actions.get(1)).accept());
        assertEquals("Agent-B", assertInstanceOf(AgentAction.GuildInvite.class, actions.get(2)).targetName());
        assertEquals("Agent-A", assertInstanceOf(AgentAction.GuildAcceptInvite.class, actions.get(3)).inviterName());
        assertEquals("Agent-B", assertInstanceOf(AgentAction.GuildLeave.class, actions.get(4)).name());
        AgentAction.GuildExpel expel = assertInstanceOf(AgentAction.GuildExpel.class, actions.get(5));
        assertEquals(42, expel.targetId());
        assertEquals("Agent-B", expel.targetName());
        AgentAction.GuildChangeRankTitle titles = assertInstanceOf(AgentAction.GuildChangeRankTitle.class, actions.get(6));
        assertEquals(List.of("Master", "Junior Master", "Member", "Officer", "Rookie"), titles.ranks());
        AgentAction.GuildChangeRank rank = assertInstanceOf(AgentAction.GuildChangeRank.class, actions.get(7));
        assertEquals(42, rank.targetId());
        assertEquals(3, rank.newRank());
        assertEquals("Guild meeting Friday", assertInstanceOf(AgentAction.GuildNotice.class, actions.get(8)).notice());
    }

    @Test
    void parsesAllianceAndMarriageAndFamilyActions() throws Exception {
        String json = """
                [
                  {"type":"AllianceInvite","guildName":"Cosmonauts"},
                  {"type":"AllianceAcceptInvite","inviterName":"Agent-A"},
                  {"type":"AllianceLeave"},
                  {"type":"Engage","targetName":"Agent-B","itemId":2240000},
                  {"type":"EngageAnswer","accept":true,"proposerName":"Agent-A"},
                  {"type":"BreakEngagement","itemId":4031357},
                  {"type":"FamilyAdd","name":"Agent-B"},
                  {"type":"FamilyAccept","accept":false,"inviterName":"Agent-A"}
                ]
                """;
        List<AgentAction> actions = HarnessActions.parse(mapper.readTree(json));
        assertEquals(8, actions.size());
        assertEquals("Cosmonauts", assertInstanceOf(AgentAction.AllianceInvite.class, actions.get(0)).guildName());
        assertEquals("Agent-A", assertInstanceOf(AgentAction.AllianceAcceptInvite.class, actions.get(1)).inviterName());
        assertInstanceOf(AgentAction.AllianceLeave.class, actions.get(2));
        AgentAction.Engage engage = assertInstanceOf(AgentAction.Engage.class, actions.get(3));
        assertEquals("Agent-B", engage.targetName());
        assertEquals(2240000, engage.itemId());
        AgentAction.EngageAnswer answer = assertInstanceOf(AgentAction.EngageAnswer.class, actions.get(4));
        assertTrue(answer.accept());
        assertEquals("Agent-A", answer.proposerName());
        assertEquals(4031357, assertInstanceOf(AgentAction.BreakEngagement.class, actions.get(5)).itemId());
        assertEquals("Agent-B", assertInstanceOf(AgentAction.FamilyAdd.class, actions.get(6)).name());
        AgentAction.FamilyAccept family = assertInstanceOf(AgentAction.FamilyAccept.class, actions.get(7));
        assertFalse(family.accept());
        assertEquals("Agent-A", family.inviterName());
    }

    @Test
    void guildChangeRankTitleRequiresFiveRanks() {
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(
                "[{\"type\":\"GuildChangeRankTitle\",\"ranks\":[\"a\",\"b\",\"c\"]}]")));
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(
                "[{\"type\":\"GuildChangeRankTitle\"}]")));
    }

    @Test
    void unknownSocialActionTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> HarnessActions.parse(mapper.readTree(
                "[{\"type\":\"GuildFly\"}]")));
    }
}
