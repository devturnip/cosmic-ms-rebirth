package net.server.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPersonaStoreTest {

    @Test
    void loadJsonMappingShape() throws IOException {
        Path file = Files.createTempFile("personas-", ".json");
        try {
            Files.writeString(file,
                    "{\"alice\":{\"description\":\"A friendly helper\",\"systemPrompt\":\"You are Alice.\"}}",
                    StandardCharsets.UTF_8);

            Map<String, AgentPersona> personas = AgentPersonaStore.load(file.toString());

            assertEquals(1, personas.size());
            AgentPersona alice = personas.get("alice");
            assertEquals("alice", alice.name());
            assertEquals("A friendly helper", alice.description());
            assertEquals("You are Alice.", alice.systemPrompt());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void loadJsonArrayShape() throws IOException {
        Path file = Files.createTempFile("personas-", ".json");
        try {
            Files.writeString(file,
                    "[{\"name\":\"bob\",\"description\":\"Brave\",\"systemPrompt\":\"You are Bob.\"}]",
                    StandardCharsets.UTF_8);

            Map<String, AgentPersona> personas = AgentPersonaStore.load(file.toString());

            assertEquals(1, personas.size());
            AgentPersona bob = personas.get("bob");
            assertEquals("bob", bob.name());
            assertEquals("Brave", bob.description());
            assertEquals("You are Bob.", bob.systemPrompt());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void loadYamlMappingShape() throws IOException {
        Path file = Files.createTempFile("personas-", ".yaml");
        try {
            Files.writeString(file,
                    "alice:\n  description: Friendly helper\n  systemPrompt: You are Alice.\n",
                    StandardCharsets.UTF_8);

            Map<String, AgentPersona> personas = AgentPersonaStore.load(file.toString());

            assertEquals(1, personas.size());
            AgentPersona alice = personas.get("alice");
            assertEquals("alice", alice.name());
            assertEquals("Friendly helper", alice.description());
            assertEquals("You are Alice.", alice.systemPrompt());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void loadJsonParsesGreetingAndTraits() throws IOException {
        Path file = Files.createTempFile("personas-", ".json");
        try {
            Files.writeString(file,
                    "{\"alice\":{\"description\":\"helper\",\"greeting\":\"Hi there!\",\"traits\":\"cheerful, chatty\"}}",
                    StandardCharsets.UTF_8);

            AgentPersona alice = AgentPersonaStore.load(file.toString()).get("alice");

            assertEquals("Hi there!", alice.greeting());
            assertEquals("cheerful, chatty", alice.traits());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void loadYamlParsesGreetingAndTraits() throws IOException {
        Path file = Files.createTempFile("personas-", ".yaml");
        try {
            Files.writeString(file,
                    "alice:\n  description: helper\n  greeting: Yo!\n  traits: brave\n",
                    StandardCharsets.UTF_8);

            AgentPersona alice = AgentPersonaStore.load(file.toString()).get("alice");

            assertEquals("Yo!", alice.greeting());
            assertEquals("brave", alice.traits());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void jsonAndYamlJoinArrayTraitsTheSameWay() throws IOException {
        Path json = Files.createTempFile("personas-", ".json");
        Path yaml = Files.createTempFile("personas-", ".yaml");
        try {
            Files.writeString(json,
                    "{\"alice\":{\"traits\":[\"cheerful\",\"chatty\"]}}", StandardCharsets.UTF_8);
            Files.writeString(yaml,
                    "alice:\n  traits:\n    - cheerful\n    - chatty\n", StandardCharsets.UTF_8);

            assertEquals("cheerful, chatty", AgentPersonaStore.load(json.toString()).get("alice").traits());
            assertEquals("cheerful, chatty", AgentPersonaStore.load(yaml.toString()).get("alice").traits());
        } finally {
            Files.deleteIfExists(json);
            Files.deleteIfExists(yaml);
        }
    }

    @Test
    void blankAndMissingPathsYieldEmptyMap() {
        assertTrue(AgentPersonaStore.load(null).isEmpty(), "null path");
        assertTrue(AgentPersonaStore.load("").isEmpty(), "empty path");
        assertTrue(AgentPersonaStore.load("/nonexistent/x.json").isEmpty(), "missing file");
    }
}
