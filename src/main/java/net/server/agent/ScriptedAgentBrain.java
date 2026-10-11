package net.server.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic, no-LLM brain used for the M0 feasibility spike, for local testing, and
 * as a zero-cost fallback. It reacts to direct mentions, whispers, party invites and GM
 * instructions.
 */
public class ScriptedAgentBrain implements AgentBrain {
    private final String personaName;
    private int replyIndex;

    public ScriptedAgentBrain(String personaName) {
        this.personaName = personaName;
    }

    @Override
    public String describe() {
        return "scripted:" + personaName;
    }

    @Override
    public List<AgentAction> decide(AgentObservation observation, List<AgentEvent> newEvents, AgentMemory memory) {
        List<AgentAction> actions = new ArrayList<>();
        for (AgentEvent event : newEvents) {
            switch (event) {
                case AgentEvent.MapChat chat -> {
                    if (mentionsName(chat.message()) && chat.speakerId() != 0) {
                        actions.add(new AgentAction.Say(nextReply()));
                    }
                }
                case AgentEvent.Whisper whisper -> actions.add(new AgentAction.Whisper(whisper.speakerName(), nextReply()));
                case AgentEvent.PartyChat chat -> {
                    if (mentionsName(chat.message())) {
                        actions.add(new AgentAction.PartySay("I'm here!"));
                    }
                }
                case AgentEvent.PartyInvite invite -> actions.add(new AgentAction.AcceptPartyInvite(invite.partyId()));
                case AgentEvent.Instruction instruction -> actions.add(new AgentAction.Say(instruction.message()));
                default -> {
                }
            }
        }

        // Opportunistic loot: pick up the first nearby dropped item (used for smoke testing).
        if (observation != null && observation.nearbyItems() != null && !observation.nearbyItems().isEmpty()) {
            actions.add(new AgentAction.PickupItem(observation.nearbyItems().get(0).objectId()));
        }
        return actions;
    }

    private boolean mentionsName(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains(personaName.toLowerCase()) || lower.contains("ai");
    }

    private String nextReply() {
        String[] replies = {
                "Hey there! Want to party up?",
                "Hello! I'm happy to tag along.",
                "Sure, let's go!",
                "Nice to meet you."
        };
        return replies[replyIndex++ % replies.length];
    }
}
