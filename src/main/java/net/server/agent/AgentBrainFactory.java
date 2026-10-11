package net.server.agent;

/**
 * Creates the {@link AgentBrain} for a newly spawned agent. Kept as a single seam so the
 * LLM implementation can be plugged in without changing call sites.
 */
public final class AgentBrainFactory {
    private AgentBrainFactory() {
    }

    public static AgentBrain create(String personaName) {
        return new ScriptedAgentBrain(personaName);
    }
}
