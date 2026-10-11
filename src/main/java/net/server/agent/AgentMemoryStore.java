package net.server.agent;

/**
 * Seam for agent memory persistence. The default implementation is in-memory only; a
 * DB-backed implementation keyed by character id can be plugged in later (see the plan's
 * {@code agent_memory} table).
 */
public class AgentMemoryStore {

    public AgentMemory load(int characterId) {
        return new AgentMemory(characterId);
    }

    public void save(AgentMemory memory) {
        // no-op default: memory is process-local until a persistent store is provided
    }
}
