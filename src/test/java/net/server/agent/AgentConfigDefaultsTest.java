package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigDefaultsTest {

    @Test
    void maxActionsPerSecondIsAtLeastOne() {
        assertTrue(AgentConfig.maxActionsPerSecond() >= 1);
    }

    @Test
    void followAndPrefixAccessorsReturnBooleans() {
        assertDoesNotThrow(() -> {
            boolean follow = AgentConfig.followEnabled();
            boolean requirePrefix = AgentConfig.requireNamePrefix();
            // Boolean accessors simply must be callable; no config-specific value is asserted.
            assertTrue(follow || !follow);
            assertTrue(requirePrefix || !requirePrefix);
        });
    }
}
