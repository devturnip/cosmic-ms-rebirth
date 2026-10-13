package net.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AgentBrain} backed by an OpenAI-compatible chat-completions endpoint using
 * tool/function calling. Tool calls are mapped, in order, to {@link AgentAction}s.
 * <p>
 * The brain is strictly reactive: it only acts on events it is given and never speaks
 * unprompted. Any failure (transport, parsing, malformed arguments) results in an empty
 * action list so a bad LLM cycle can never break the agent.
 */
public class LlmAgentBrain implements AgentBrain {
    private static final Logger log = LoggerFactory.getLogger(LlmAgentBrain.class);
    private static final int MAX_MESSAGE_LENGTH = 120;

    private final String personaName;
    private final String personaDescription;
    private final OpenAiChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmAgentBrain(String personaName) {
        this(personaName, null);
    }

    public LlmAgentBrain(String personaName, String personaDescription) {
        this.personaName = personaName == null || personaName.isBlank() ? "Agent" : personaName;
        this.personaDescription = personaDescription;
        this.client = new OpenAiChatClient();
    }

    @Override
    public String describe() {
        return "llm:" + AgentConfig.model() + ":" + personaName;
    }

    @Override
    public List<AgentAction> decide(AgentObservation observation, List<AgentEvent> newEvents, AgentMemory memory) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(observation, newEvents, memory);
            OpenAiChatClient.ChatResult result = client.chat(systemPrompt, userPrompt);

            List<AgentAction> actions = new ArrayList<>();
            for (OpenAiChatClient.ToolCall call : result.toolCalls()) {
                AgentAction action = toAction(call);
                if (action != null) {
                    actions.add(action);
                }
            }
            return actions;
        } catch (Throwable t) {
            log.warn("LLM brain {} failed to decide; doing nothing this cycle", personaName, t);
            return List.of();
        }
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(personaName).append(", a player character in MapleStory v83.\n");
        if (personaDescription != null && !personaDescription.isBlank()) {
            sb.append(personaDescription.strip()).append('\n');
        }
        sb.append("MapleStory is a 2D side-scrolling MMORPG; players chat in map chat, party chat and whispers, ")
                .append("form parties, and fight monsters together.\n");
        sb.append("You are a strictly reactive player agent. Only speak when addressed, when answering a whisper ")
                .append("or party chat message, or when responding to a party invite. If none of those apply, choose idle.\n");
        sb.append("Keep every message under ").append(MAX_MESSAGE_LENGTH).append(" characters. ")
                .append("Never start a message with '/'. Stay in character, be friendly, and keep chat appropriate ")
                .append("for all ages.\n");
        sb.append("Respond only by calling the provided tools; call idle (or no tool) when nothing is required.");
        return sb.toString();
    }

    private String buildUserPrompt(AgentObservation observation, List<AgentEvent> newEvents, AgentMemory memory) {
        StringBuilder sb = new StringBuilder();
        if (observation != null) {
            sb.append("Current state:\n");
            sb.append("- Name: ").append(observation.agentName()).append('\n');
            sb.append("- Level ").append(observation.level()).append(' ').append(observation.jobName())
                    .append(" (job id ").append(observation.jobId()).append(")\n");
            sb.append("- Map: ").append(observation.mapName()).append(" (").append(observation.mapId()).append(") in ")
                    .append(observation.streetName()).append('\n');
            sb.append("- HP ").append(observation.hp()).append('/').append(observation.maxHp())
                    .append(", MP ").append(observation.mp()).append('/').append(observation.maxMp()).append('\n');
            sb.append("- Position: (").append(observation.x()).append(", ").append(observation.y()).append(")\n");
            if (observation.rangedWeapon()) {
                if (observation.outOfAmmo()) {
                    sb.append("- Ranged weapon: OUT OF AMMO (recharge at a nearby shop NPC before attacking)\n");
                } else if (observation.ammoCount() > 0) {
                    sb.append("- Ranged weapon: ").append(observation.ammoCount())
                            .append(" projectile(s) available\n");
                } else {
                    sb.append("- Ranged weapon: Soul Arrow active (no ammo consumed)\n");
                }
            }
            if (observation.buffSkills() != null && !observation.buffSkills().isEmpty()) {
                sb.append("- Buff skills you can cast: ").append(observation.buffSkills()).append('\n');
            }
            if (observation.attackSkills() != null && !observation.attackSkills().isEmpty()) {
                sb.append("- Attack skills you can use: ").append(observation.attackSkills()).append('\n');
            }
            if (observation.healSkills() != null && !observation.healSkills().isEmpty()) {
                sb.append("- Heal skills you can cast: ").append(observation.healSkills()).append('\n');
            }
            appendPlayers(sb, "Nearby players", observation.nearbyPlayers());
            appendPlayers(sb, "Party members", observation.partyMembers());
            appendMonsters(sb, observation.nearbyMonsters());
            appendItems(sb, observation.nearbyItems());
            appendNpcs(sb, observation.nearbyNpcs());
        }

        sb.append("New events since your last decision:\n");
        if (newEvents == null || newEvents.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (AgentEvent event : newEvents) {
                sb.append("- ").append(describeEvent(event)).append('\n');
            }
        }

        String digest = memory == null ? "" : memory.digest();
        if (digest != null && !digest.isBlank()) {
            sb.append("Memory:\n").append(digest);
        }
        sb.append("What do you do?");
        return sb.toString();
    }

    private void appendPlayers(StringBuilder sb, String label, List<AgentObservation.ObservedPlayer> players) {
        if (players == null || players.isEmpty()) {
            sb.append(label).append(": none\n");
            return;
        }
        sb.append(label).append(":\n");
        for (AgentObservation.ObservedPlayer player : players) {
            sb.append("- ").append(player.name())
                    .append(" (level ").append(player.level())
                    .append(", id ").append(player.id());
            if (player.partyMember()) {
                sb.append(", party member");
            }
            if (player.leader()) {
                sb.append(", party leader");
            }
            if (!player.sameMap()) {
                sb.append(", different map");
            }
            sb.append(")\n");
        }
    }

    private void appendMonsters(StringBuilder sb, List<AgentObservation.ObservedMonster> monsters) {
        if (monsters == null || monsters.isEmpty()) {
            sb.append("Nearby monsters: none\n");
            return;
        }
        sb.append("Nearby monsters: ");
        for (int i = 0; i < monsters.size(); i++) {
            AgentObservation.ObservedMonster monster = monsters.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(monster.name())
                    .append(" (id ").append(monster.mobId())
                    .append(", lvl ").append(monster.level())
                    .append(", hp ").append(monster.hp()).append('/').append(monster.maxHp())
                    .append(", object ").append(monster.objectId())
                    .append(") at (").append(monster.x()).append(", ").append(monster.y()).append(')');
        }
        sb.append('\n');
    }

    private void appendItems(StringBuilder sb, List<AgentObservation.ObservedItem> items) {
        if (items == null || items.isEmpty()) {
            sb.append("Nearby items: none\n");
            return;
        }
        sb.append("Nearby items: ");
        for (int i = 0; i < items.size(); i++) {
            AgentObservation.ObservedItem item = items.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(item.itemId()).append(" x").append(item.quantity())
                    .append(" (object ").append(item.objectId())
                    .append(", owner ").append(item.ownerId())
                    .append(") at (").append(item.x()).append(", ").append(item.y()).append(')');
        }
        sb.append('\n');
    }

    private void appendNpcs(StringBuilder sb, List<AgentObservation.ObservedNpc> npcs) {
        if (npcs == null || npcs.isEmpty()) {
            sb.append("Nearby NPCs: none\n");
            return;
        }
        sb.append("Nearby NPCs: ");
        for (int i = 0; i < npcs.size(); i++) {
            AgentObservation.ObservedNpc npc = npcs.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(npc.npcId())
                    .append(" (object ").append(npc.objectId())
                    .append(") at (").append(npc.x()).append(", ").append(npc.y()).append(')');
        }
        sb.append('\n');
    }

    private String describeEvent(AgentEvent event) {
        return switch (event) {
            case AgentEvent.MapChat chat -> chat.speakerName() + " said in map chat: \"" + chat.message() + "\"";
            case AgentEvent.PartyChat chat -> chat.speakerName() + " said in party chat: \"" + chat.message() + "\"";
            case AgentEvent.Whisper whisper -> whisper.speakerName() + " whispered you: \"" + whisper.message() + "\"";
            case AgentEvent.PartyInvite invite -> invite.inviterName() + " invited you to party " + invite.partyId();
            case AgentEvent.PartyUpdate update -> "Party update: " + update.description();
            case AgentEvent.MapChanged changed -> "You changed map to " + changed.mapId();
            case AgentEvent.Tick ignored -> "Heartbeat tick.";
            case AgentEvent.Instruction instruction -> "Instruction from an operator: \"" + instruction.message() + "\"";
        };
    }

    AgentAction toAction(OpenAiChatClient.ToolCall call) {
        JsonNode args;
        try {
            String json = call.argumentsJson();
            args = json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            log.warn("LLM brain {} received invalid arguments for tool {}", personaName, call.name(), e);
            return null;
        }

        return switch (call.name()) {
            case "say" -> {
                String message = chat(args, "message");
                yield message == null ? null : new AgentAction.Say(message);
            }
            case "party_say" -> {
                String message = chat(args, "message");
                yield message == null ? null : new AgentAction.PartySay(message);
            }
            case "whisper" -> {
                String target = text(args, "target");
                String message = chat(args, "message");
                yield target == null || message == null ? null : new AgentAction.Whisper(target, message);
            }
            case "invite_to_party" -> {
                String target = text(args, "target");
                yield target == null ? null : new AgentAction.InviteToParty(target);
            }
            case "accept_party_invite" -> {
                JsonNode partyId = args.path("party_id");
                yield partyId.canConvertToInt() ? new AgentAction.AcceptPartyInvite(partyId.asInt()) : null;
            }
            case "deny_party_invite" -> {
                String inviter = text(args, "inviter");
                yield inviter == null ? null : new AgentAction.DenyPartyInvite(inviter);
            }
            case "leave_party" -> new AgentAction.LeaveParty();
            case "move_to" -> {
                JsonNode x = args.path("x");
                JsonNode y = args.path("y");
                yield x.canConvertToInt() && y.canConvertToInt()
                        ? new AgentAction.MoveTo(x.asInt(), y.asInt())
                        : null;
            }
            case "pickup_item" -> {
                JsonNode objectId = args.path("object_id");
                yield objectId.canConvertToInt() ? new AgentAction.PickupItem(objectId.asInt()) : null;
            }
            case "attack" -> {
                JsonNode target = args.path("target_object_id");
                if (!target.canConvertToInt()) {
                    yield null;
                }
                JsonNode skill = args.path("skill_id");
                yield skill.canConvertToInt() && skill.asInt() > 0
                        ? new AgentAction.SkillAttack(target.asInt(), skill.asInt())
                        : new AgentAction.Attack(target.asInt());
            }
            case "attack_many" -> {
                List<Integer> targets = intArray(args, "target_object_ids");
                if (targets.isEmpty()) {
                    yield null;
                }
                JsonNode skill = args.path("skill_id");
                yield new AgentAction.SkillAttackMany(targets, skill.canConvertToInt() ? skill.asInt() : 0);
            }
            case "recharge_ammo" -> new AgentAction.RechargeAmmo();
            case "respawn" -> new AgentAction.Respawn();
            case "cast_buff" -> {
                JsonNode skillId = args.path("skill_id");
                yield skillId.canConvertToInt() ? new AgentAction.CastBuff(skillId.asInt()) : null;
            }
            case "cast_heal" -> {
                JsonNode skillId = args.path("skill_id");
                yield new AgentAction.CastHeal(skillId.canConvertToInt() ? skillId.asInt() : 0);
            }
            case "remember" -> {
                String fact = text(args, "fact");
                yield fact == null ? null : new AgentAction.Remember(fact);
            }
            case "idle" -> new AgentAction.Idle();
            default -> null;
        };
    }

    private String text(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Reads an array of integers, dropping non-numeric/duplicate entries and bounding the size
     * so a hallucinated list cannot force unbounded parsing or map lookups.
     */
    private List<Integer> intArray(JsonNode args, String field) {
        JsonNode node = args.path(field);
        List<Integer> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        int cap = AgentConfig.aoeMaxTargets(); // already clamped to [1, 15]
        for (JsonNode element : node) {
            if (values.size() >= cap) {
                break;
            }
            if (element.canConvertToInt()) {
                int value = element.asInt();
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String chat(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_MESSAGE_LENGTH ? value : value.substring(0, MAX_MESSAGE_LENGTH);
    }
}
