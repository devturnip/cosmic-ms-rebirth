package net.server.agent;

import config.YamlConfig;

/**
 * Typed access to the LLM-agent configuration, with sane defaults so a missing or empty
 * {@code config.yaml} entry never breaks the server.
 */
public final class AgentConfig {
    private static final int DEFAULT_MAX_AGENTS = 4;
    private static final long DEFAULT_TICK_MS = 2000L;
    private static final int DEFAULT_MAX_CONCURRENT_CALLS = 4;
    private static final double DEFAULT_VIEW_RANGE_SQ = 722500.0; // 850^2, matches MapleMap
    private static final String DEFAULT_NAME_PREFIX = "[AI]";

    private AgentConfig() {
    }

    public static boolean enabled() {
        return YamlConfig.config.server.USE_LLM_AGENTS;
    }

    public static int maxAgents() {
        return YamlConfig.config.server.LLM_AGENTS_MAX > 0 ? YamlConfig.config.server.LLM_AGENTS_MAX : DEFAULT_MAX_AGENTS;
    }

    public static long tickMs() {
        return YamlConfig.config.server.LLM_AGENT_TICK_MS > 0 ? YamlConfig.config.server.LLM_AGENT_TICK_MS : DEFAULT_TICK_MS;
    }

    public static boolean omniscient() {
        return "OMNISCIENT".equalsIgnoreCase(perception());
    }

    public static String perception() {
        String value = YamlConfig.config.server.LLM_AGENT_PERCEPTION;
        return value == null || value.isBlank() ? "BOUNDED" : value;
    }

    public static double perceptionRangeSq() {
        double value = YamlConfig.config.server.LLM_AGENT_VIEW_RANGE;
        return value > 0 ? value : DEFAULT_VIEW_RANGE_SQ;
    }

    public static String apiUrl() {
        return orEmpty(YamlConfig.config.server.LLM_API_URL);
    }

    public static String apiKey() {
        return orEmpty(YamlConfig.config.server.LLM_API_KEY);
    }

    public static String model() {
        return orEmpty(YamlConfig.config.server.LLM_MODEL);
    }

    public static int maxConcurrentCalls() {
        return YamlConfig.config.server.LLM_MAX_CONCURRENT_CALLS > 0
                ? YamlConfig.config.server.LLM_MAX_CONCURRENT_CALLS
                : DEFAULT_MAX_CONCURRENT_CALLS;
    }

    public static String namePrefix() {
        String value = YamlConfig.config.server.LLM_AGENT_NAME_PREFIX;
        return value == null ? DEFAULT_NAME_PREFIX : value;
    }

    public static boolean contentFilter() {
        return YamlConfig.config.server.LLM_AGENT_CONTENT_FILTER;
    }

    public static String personasFile() {
        return orEmpty(YamlConfig.config.server.LLM_AGENT_PERSONAS_FILE);
    }

    public static boolean memoryPersist() {
        return YamlConfig.config.server.LLM_AGENT_MEMORY_PERSIST;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
