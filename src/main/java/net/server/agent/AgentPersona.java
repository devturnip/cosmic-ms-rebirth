package net.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional persona metadata for an {@link LlmAgentBrain}: a display name, a short
 * description and an optional system prompt. A personas file is configured through
 * {@link AgentConfig#personasFile()}.
 */
public record AgentPersona(String name, String description, String systemPrompt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Loads personas from the path configured in {@link AgentConfig#personasFile()}. */
    public static Map<String, AgentPersona> loadFromFile() {
        return loadFromFile(AgentConfig.personasFile());
    }

    /**
     * Loads personas from a JSON file. The file may be either a JSON array of persona
     * objects or an object keyed by persona name whose values are persona objects.
     *
     * @return an ordered map of persona name to persona; empty if the path is blank,
     * missing or invalid.
     */
    public static Map<String, AgentPersona> loadFromFile(String path) {
        Map<String, AgentPersona> personas = new LinkedHashMap<>();
        if (path == null || path.isBlank()) {
            return personas;
        }
        Path file = Path.of(path.strip());
        if (!Files.isRegularFile(file)) {
            return personas;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            if (root == null || root.isNull()) {
                return personas;
            }
            if (root.isArray()) {
                for (JsonNode node : root) {
                    add(personas, node, null);
                }
            } else if (root.isObject()) {
                var fields = root.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    add(personas, entry.getValue(), entry.getKey());
                }
            }
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
        return personas;
    }

    private static void add(Map<String, AgentPersona> personas, JsonNode node, String fallbackName) {
        if (node == null || !node.isObject()) {
            return;
        }
        String name = node.path("name").isTextual() ? node.get("name").asText() : fallbackName;
        if (name == null || name.isBlank()) {
            return;
        }
        String description = node.path("description").isTextual() ? node.get("description").asText() : "";
        String systemPrompt = node.path("systemPrompt").isTextual() ? node.get("systemPrompt").asText() : "";
        personas.put(name, new AgentPersona(name, description, systemPrompt));
    }
}
