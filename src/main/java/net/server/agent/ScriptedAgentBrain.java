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
    public String greeting() {
        return "Hey! Want to party up?";
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
        return containsWord(lower, personaName.toLowerCase()) || containsWord(lower, "ai");
    }

    /** Whole-word (alphanumeric-boundary) match, so "said"/"again" do not trigger "ai". */
    private static boolean containsWord(String haystack, String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(word, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
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
