package net.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the {@link AgentBrain} for a newly spawned agent. Kept as a single seam so the
 * LLM implementation can be plugged in without changing call sites.
 */
public final class AgentBrainFactory {
    private static final Logger log = LoggerFactory.getLogger(AgentBrainFactory.class);

    private AgentBrainFactory() {
    }

    public static AgentBrain create(String personaName) {
        String apiUrl = AgentConfig.apiUrl();
        String model = AgentConfig.model();
        if (!apiUrl.isBlank() && !model.isBlank()) {
            try {
                AgentPersona persona = AgentPersonaStore.getInstance().find(personaName).orElse(null);
                if (persona != null) {
                    String prompt = persona.systemPrompt();
                    if (prompt == null || prompt.isBlank()) {
                        prompt = persona.description();
                    }
                    return new LlmAgentBrain(personaName, prompt, persona.greeting(), persona.traits());
                }
                return new LlmAgentBrain(personaName);
            } catch (Throwable t) {
                log.warn("Failed to create LLM brain for {}; falling back to scripted brain", personaName, t);
            }
        }
        return new ScriptedAgentBrain(personaName);
    }
}
