package net.server.agent.harness;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate tests: ops must refuse when the harness is disabled, and the config accessors must be
 * consistent with the committed config.yaml (whatever profile it ships).
 */
class TestHarnessGateTest {

    @Test
    void enabledFlagMatchesConfig() {
        assertEquals(YamlConfig.config.server.USE_TEST_HARNESS, TestHarnessConfig.enabled());
    }

    @Test
    void opsRefuseToRunWhenDisabled() {
        if (TestHarnessConfig.enabled()) {
            return; // dev profile runs with the harness on; gate behavior is covered by MCP tests
        }
        assertThrows(IllegalStateException.class, () -> TestOps.heal(null));
        assertThrows(IllegalStateException.class, () -> TestHarnessService.getInstance().listSessions());
        assertThrows(IllegalStateException.class, () -> TestHarnessService.getInstance().startSession(0, 1, 0, 0, 1));
    }

    @Test
    void configDefaultsAreSane() {
        assertTrue(TestHarnessConfig.maxSessions() > 0);
        assertTrue(TestHarnessConfig.httpPort() > 0);
        assertTrue(List.of("stdio", "http").contains(TestHarnessConfig.transport()));
    }
}
