package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void perceptionRangeSqIsPositive() {
        assertTrue(AgentConfig.perceptionRangeSq() > 0);
    }

    @Test
    void limitsHaveSaneDefaults() {
        assertTrue(AgentConfig.maxAgents() >= 1);
        assertTrue(AgentConfig.tickMs() > 0);
        assertTrue(AgentConfig.maxConcurrentCalls() >= 1);
    }

    @Test
    void namePrefixIsNonNull() {
        assertNotNull(AgentConfig.namePrefix());
    }
}
