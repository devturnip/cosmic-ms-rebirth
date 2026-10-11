package net.server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedAgentBrainTest {

    private static final String PERSONA = "Alice";

    private final ScriptedAgentBrain brain = new ScriptedAgentBrain(PERSONA);
    private final AgentMemory memory = new AgentMemory(1);

    private static AgentObservation observation() {
        return new AgentObservation(
                PERSONA, 1, 0, "Beginner", 100000000, "Henesys", "Henesys",
                100, 100, 100, 100, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void mapChatMentioningNameProducesSingleSay() {
        AgentEvent.MapChat chat = new AgentEvent.MapChat(42, "Bob", "Hello Alice, how are you?", false);

        List<AgentAction> actions = brain.decide(observation(), List.of(chat), memory);

        assertEquals(1, actions.size());
        assertInstanceOf(AgentAction.Say.class, actions.get(0));
    }

    @Test
    void whisperProducesWhisperAddressedBackToSpeaker() {
        AgentEvent.Whisper whisper = new AgentEvent.Whisper(7, "Charlie", "are you there?");

        List<AgentAction> actions = brain.decide(observation(), List.of(whisper), memory);

        assertEquals(1, actions.size());
        AgentAction.Whisper reply = assertInstanceOf(AgentAction.Whisper.class, actions.get(0));
        assertEquals("Charlie", reply.targetName());
    }

    @Test
    void partyInviteProducesAcceptPartyInviteWithPartyId() {
        AgentEvent.PartyInvite invite = new AgentEvent.PartyInvite(9, "Dana", 1337);

        List<AgentAction> actions = brain.decide(observation(), List.of(invite), memory);

        assertEquals(1, actions.size());
        AgentAction.AcceptPartyInvite accept = assertInstanceOf(AgentAction.AcceptPartyInvite.class, actions.get(0));
        assertEquals(1337, accept.partyId());
    }

    @Test
    void instructionProducesSayContainingInstructionText() {
        AgentEvent.Instruction instruction = new AgentEvent.Instruction("follow me to the dungeon");

        List<AgentAction> actions = brain.decide(observation(), List.of(instruction), memory);

        assertEquals(1, actions.size());
        AgentAction.Say say = assertInstanceOf(AgentAction.Say.class, actions.get(0));
        assertTrue(say.message().contains("follow me to the dungeon"));
    }

    @Test
    void unrelatedMapChatProducesNoActions() {
        AgentEvent.MapChat chat = new AgentEvent.MapChat(42, "Bob", "good morning everyone", false);

        List<AgentAction> actions = brain.decide(observation(), List.of(chat), memory);

        assertTrue(actions.isEmpty());
    }
}
