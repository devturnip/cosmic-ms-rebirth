package net.server.agent.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate tests: with the committed config (USE_TEST_HARNESS: false) the harness must be inert —
 * no sessions, no ops, no state mutation. The config-dependent defaults are verified as well.
 */
class TestHarnessGateTest {

    @Test
    void harnessIsDisabledInTestConfig() {
        assertFalse(TestHarnessConfig.enabled(), "USE_TEST_HARNESS must default to false");
    }

    @Test
    void opsRefuseToRunWhenDisabled() {
        assertThrows(IllegalStateException.class, () -> TestOps.heal(null));
        assertThrows(IllegalStateException.class, () -> TestHarnessService.getInstance().listSessions());
        assertThrows(IllegalStateException.class, () -> TestHarnessService.getInstance().startSession(0, 1, 0, 0));
    }

    @Test
    void configDefaultsAreSane() {
        assertTrue(TestHarnessConfig.maxSessions() > 0);
        assertTrue(TestHarnessConfig.httpPort() > 0);
        assertTrue(List.of("stdio", "http").contains(TestHarnessConfig.transport()));
    }
}
