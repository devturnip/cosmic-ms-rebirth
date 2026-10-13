package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentBrainGreetingTest {

    @Test
    void scriptedBrainHasADeterministicGreeting() {
        assertFalse(new ScriptedAgentBrain("Alice").greeting().isBlank());
    }

    @Test
    void llmBrainUsesThePersonaGreeting() {
        LlmAgentBrain brain = new LlmAgentBrain("Alice", "kind cleric", "Heya, want to team up?", "cheerful");

        assertEquals("Heya, want to team up?", brain.greeting());
    }

    @Test
    void llmBrainFallsBackToTheDefaultGreeting() {
        LlmAgentBrain brain = new LlmAgentBrain("Alice");

        assertEquals("Hi! Want to party up?", brain.greeting());
    }
}
