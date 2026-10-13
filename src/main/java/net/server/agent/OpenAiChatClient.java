package net.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OpenAI-compatible chat-completions client built on the Java 21
 * {@link HttpClient}. Posts a system/user message pair plus the agent tool schema and
 * returns the first choice's content and tool calls.
 * <p>
 * The endpoint, key and model are read from {@link AgentConfig} on every call so config
 * changes take effect without restarting. The API key is never included in error messages.
 */
public class OpenAiChatClient {
    private static final int MAX_ERROR_BODY_CHARS = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArrayNode toolsSchema;

    public OpenAiChatClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.toolsSchema = buildToolsSchema(mapper);
    }

    /**
     * Sends one chat-completions request.
     *
     * @throws RuntimeException on a missing endpoint, transport failure, non-2xx status or
     *                          an unparseable response body.
     */
    public ChatResult chat(String systemPrompt, String userPrompt) {
        String apiUrl = AgentConfig.apiUrl();
        if (apiUrl.isBlank()) {
            throw new RuntimeException("LLM API URL is not configured");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", AgentConfig.model());
        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt == null ? "" : systemPrompt);
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt == null ? "" : userPrompt);
        body.set("tools", toolsSchema);
        body.put("tool_choice", "auto");
        body.put("temperature", 0.8);

        final String requestBody;
        try {
            requestBody = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize LLM request", e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        String apiKey = AgentConfig.apiKey();
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM request was interrupted", e);
        }

        String responseBody = response.body() == null ? "" : response.body();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("LLM request returned HTTP " + status + ": " + truncate(responseBody));
        }

        try {
            return parseResponse(responseBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse LLM response: " + truncate(responseBody), e);
        }
    }

    private ChatResult parseResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new ChatResult(null, List.of());
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isTextual() ? message.get("content").asText() : null;

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                String name = function.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String arguments = function.path("arguments").isTextual()
                        ? function.get("arguments").asText()
                        : "{}";
                toolCalls.add(new ToolCall(name, arguments));
            }
        }
        return new ChatResult(content, toolCalls);
    }

    private static ArrayNode buildToolsSchema(ObjectMapper mapper) {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(function(mapper, "say", "Speak in general (map) chat to everyone nearby.",
                new Param("message", "string", "The message to say.", true)));
        tools.add(function(mapper, "party_say", "Speak in party chat to the agent's party members.",
                new Param("message", "string", "The message to say.", true)));
        tools.add(function(mapper, "whisper", "Send a private whisper to a player by name.",
                new Param("target", "string", "The recipient's character name.", true),
                new Param("message", "string", "The message to whisper.", true)));
        tools.add(function(mapper, "invite_to_party", "Invite a nearby player to the agent's party.",
                new Param("target", "string", "The name of the player to invite.", true)));
        tools.add(function(mapper, "accept_party_invite", "Accept a pending party invitation.",
                new Param("party_id", "integer", "The id of the party to join.", true)));
        tools.add(function(mapper, "deny_party_invite", "Decline a pending party invitation.",
                new Param("inviter", "string", "The name of the player who sent the invite.", true)));
        tools.add(function(mapper, "leave_party", "Leave the agent's current party.", new Param[0]));
        tools.add(function(mapper, "move_to", "Walk the agent to a position on the current map.",
                new Param("x", "integer", "Target x coordinate.", true),
                new Param("y", "integer", "Target y coordinate.", true)));
        tools.add(function(mapper, "pickup_item", "Pick up a dropped item by its map object id.",
                new Param("object_id", "integer", "The map object id of the item to pick up.", true)));
        tools.add(function(mapper, "attack", "Attack a nearby monster, optionally with a learned skill.",
                new Param("target_object_id", "integer", "The map object id of the monster to attack.", true),
                new Param("skill_id", "integer", "Optional attack skill id; omit for a basic attack.", false)));
        tools.add(function(mapper, "attack_many", "Attack several nearby monsters at once with a learned damaging skill. Falls back to a single target when multi-target attacks are disabled or the skill is unusable.",
                Param.arrayOfInt("target_object_ids", "The map object ids of the monsters to attack, nearest first.", true),
                new Param("skill_id", "integer", "Optional attack skill id; omit for a basic attack on the first target.", false)));
        tools.add(function(mapper, "respawn", "Respawn at the return point after dying.", new Param[0]));
        tools.add(function(mapper, "cast_buff", "Cast a buff skill the agent has learned.",
                new Param("skill_id", "integer", "The skill id of the buff to cast.", true)));
        tools.add(function(mapper, "cast_heal", "Cast a heal skill to restore the agent and nearby party members.",
                new Param("skill_id", "integer", "Heal skill id; 0 or omitted to auto-detect the learned heal skill.", false)));
        tools.add(function(mapper, "recharge_ammo", "Recharge throwing stars or bullets at a nearby shop NPC; costs mesos. Arrows cannot be recharged and must be bought.", new Param[0]));
        tools.add(function(mapper, "remember", "Persist a durable fact about a player or event.",
                new Param("fact", "string", "The fact to remember.", true)));
        tools.add(function(mapper, "idle", "Do nothing this cycle.", new Param[0]));
        return tools;
    }

    private static ObjectNode function(ObjectMapper mapper, String name, String description, Param... parameters) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);

        ObjectNode schema = function.putObject("parameters");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Param parameter : parameters) {
            ObjectNode property = properties.putObject(parameter.name());
            property.put("type", parameter.type());
            property.put("description", parameter.description());
            if (parameter.itemType() != null) {
                property.putObject("items").put("type", parameter.itemType());
            }
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        return tool;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        if (trimmed.length() <= MAX_ERROR_BODY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }

    /** The first choice's message content and any tool calls, in the order returned. */
    public record ChatResult(String content, List<ToolCall> toolCalls) {
    }

    /** A single tool/function call requested by the model. */
    public record ToolCall(String name, String argumentsJson) {
    }

    private record Param(String name, String type, String description, boolean required, String itemType) {
        Param(String name, String type, String description, boolean required) {
            this(name, type, description, required, null);
        }

        static Param arrayOfInt(String name, String description, boolean required) {
            return new Param(name, "array", description, required, "integer");
        }
    }
}
