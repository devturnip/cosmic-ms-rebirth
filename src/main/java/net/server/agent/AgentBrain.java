package net.server.agent;

import java.util.List;

/**
 * The decision-making boundary of an agent. Implementations may call an LLM
 * (see {@code LlmAgentBrain}) or be fully deterministic (see {@link ScriptedAgentBrain}).
 * <p>
 * Implementations may block: they are always invoked off the Netty event loop, on the
 * agent manager's brain executor.
 */
public interface AgentBrain {

    /** A short human-readable description used in logs/admin output. */
    String describe();

    /**
     * Decide what to do next.
     *
     * @param observation what the agent can currently perceive
     * @param newEvents   events received since the last decision (may be empty)
     * @param memory      the agent's memory (already updated with recent events before this call)
     * @return ordered actions to execute; an empty list means "do nothing"
     */
    List<AgentAction> decide(AgentObservation observation, List<AgentEvent> newEvents, AgentMemory memory);
}
