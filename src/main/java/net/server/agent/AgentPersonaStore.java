package net.server.agent;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lazily loaded, process-wide cache of {@link AgentPersona}s defined by
 * {@link AgentConfig#personasFile()}.
 * <p>
 * Two file formats are supported and both accept the same two document shapes:
 * <ul>
 *     <li>a list of {@code {name, description, systemPrompt}} objects, or</li>
 *     <li>a mapping of {@code name -> {description, systemPrompt}}.</li>
 * </ul>
 * JSON files ({@code .json}) are parsed through {@link AgentPersona#loadFromFile(String)};
 * YAML files ({@code .yaml}/{@code .yml}) through {@link YamlReader}.
 * <p>
 * Loading never throws: a blank path, a missing file or malformed content simply yields an
 * empty store (with a warning), so a bad personas file can never break agent spawning.
 */
public final class AgentPersonaStore {
    private static final Logger log = LoggerFactory.getLogger(AgentPersonaStore.class);
    private static final AgentPersonaStore INSTANCE = new AgentPersonaStore();

    private volatile Map<String, AgentPersona> personas;

    private AgentPersonaStore() {
    }

    public static AgentPersonaStore getInstance() {
        return INSTANCE;
    }

    /**
     * Looks up a persona by its exact name.
     *
     * @return the persona if defined, otherwise {@link Optional#empty()}.
     */
    public Optional<AgentPersona> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(personas().get(name));
    }

    /** Discards the cached personas and re-reads the configured file immediately. */
    public void reload() {
        synchronized (this) {
            this.personas = load(AgentConfig.personasFile());
        }
    }

    private Map<String, AgentPersona> personas() {
        Map<String, AgentPersona> current = this.personas;
        if (current == null) {
            synchronized (this) {
                current = this.personas;
                if (current == null) {
                    current = load(AgentConfig.personasFile());
                    this.personas = current;
                }
            }
        }
        return current;
    }

    private static Map<String, AgentPersona> load(String path) {
        if (path == null || path.isBlank()) {
            log.warn("No agent personas file configured (LLM_AGENT_PERSONAS_FILE is blank); no personas loaded");
            return Map.of();
        }
        try {
            Path file = Path.of(path.strip());
            if (!Files.isRegularFile(file)) {
                log.warn("Agent personas file '{}' does not exist or is not a regular file; no personas loaded", file);
                return Map.of();
            }
            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            Map<String, AgentPersona> loaded = fileName.endsWith(".yaml") || fileName.endsWith(".yml")
                    ? loadYaml(file)
                    : AgentPersona.loadFromFile(file.toString());
            if (loaded.isEmpty()) {
                log.warn("Agent personas file '{}' contained no valid personas; no personas loaded", file);
                return Map.of();
            }
            log.info("Loaded {} agent persona(s) from {}", loaded.size(), file);
            return Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        } catch (Throwable t) {
            log.warn("Failed to read agent personas file '{}'; no personas loaded", path, t);
            return Map.of();
        }
    }

    private static Map<String, AgentPersona> loadYaml(Path file) throws Exception {
        Map<String, AgentPersona> personas = new LinkedHashMap<>();
        try (YamlReader reader = new YamlReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            Object root = reader.read();
            if (root instanceof List<?> list) {
                for (Object item : list) {
                    addYaml(personas, item, null);
                }
            } else if (root instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    addYaml(personas, entry.getValue(), entry.getKey() == null ? null : String.valueOf(entry.getKey()));
                }
            }
        }
        return personas;
    }

    private static void addYaml(Map<String, AgentPersona> personas, Object node, String fallbackName) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        String name = value(map.get("name"));
        if (name.isBlank() && fallbackName != null) {
            name = fallbackName;
        }
        if (name.isBlank()) {
            return;
        }
        personas.put(name, new AgentPersona(name, value(map.get("description")), value(map.get("systemPrompt"))));
    }

    private static String value(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
