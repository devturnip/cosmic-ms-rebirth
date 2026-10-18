package net.server.agent.harness;

import config.YamlConfig;

/**
 * Typed access to the test-harness configuration. The harness is the MCP surface that lets an
 * external LLM agent (pi / Claude Code) drive end-to-end gameplay tests: provisioning ephemeral
 * characters, observing structured state, submitting player actions and using setup "cheat" ops.
 * <p>
 * Everything is default-off: a vanilla server has no test surface at all. Harness sessions are
 * independent of the player-facing agent feature ({@code USE_LLM_AGENTS}) — see
 * {@code AgentManager#spawnHarness}.
 */
public final class TestHarnessConfig {
    private static final int DEFAULT_MAX_SESSIONS = 4;
    private static final int DEFAULT_HTTP_PORT = 8123;
    private static final String DEFAULT_TRANSPORT = "http";

    private TestHarnessConfig() {
    }

    /** Master switch. When false, no harness session, op or MCP server may start. */
    public static boolean enabled() {
        return YamlConfig.config.server.USE_TEST_HARNESS;
    }

    public static int maxSessions() {
        return YamlConfig.config.server.TEST_HARNESS_MAX_SESSIONS > 0
                ? YamlConfig.config.server.TEST_HARNESS_MAX_SESSIONS
                : DEFAULT_MAX_SESSIONS;
    }

    /** {@code "http"} (streamable HTTP, for docker/remote agents) or {@code "stdio"} (embedded). */
    public static String transport() {
        String value = YamlConfig.config.server.TEST_HARNESS_TRANSPORT;
        return value == null || value.isBlank() ? DEFAULT_TRANSPORT : value.strip().toLowerCase();
    }

    public static int httpPort() {
        return YamlConfig.config.server.TEST_HARNESS_HTTP_PORT > 0
                ? YamlConfig.config.server.TEST_HARNESS_HTTP_PORT
                : DEFAULT_HTTP_PORT;
    }
}
