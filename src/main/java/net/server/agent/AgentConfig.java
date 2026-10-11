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

    public static int maxActionsPerSecond() {
        return YamlConfig.config.server.LLM_AGENT_MAX_ACTIONS_PER_SEC > 0
                ? YamlConfig.config.server.LLM_AGENT_MAX_ACTIONS_PER_SEC
                : 4;
    }

    public static boolean requireNamePrefix() {
        return YamlConfig.config.server.LLM_AGENT_REQUIRE_PREFIX;
    }

    public static boolean followEnabled() {
        return YamlConfig.config.server.LLM_AGENT_FOLLOW;
    }

    public static boolean autoSustain() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_SUSTAIN;
    }

    public static boolean autoCombat() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_COMBAT;
    }

    public static int hpPotionPercent() {
        int value = YamlConfig.config.server.LLM_AGENT_HP_POTION_PERCENT;
        return value > 0 && value <= 100 ? value : 50;
    }

    public static int mpPotionPercent() {
        int value = YamlConfig.config.server.LLM_AGENT_MP_POTION_PERCENT;
        return value > 0 && value <= 100 ? value : 30;
    }

    public static double attackRangeSq() {
        double value = YamlConfig.config.server.LLM_AGENT_ATTACK_RANGE_SQ;
        return value > 0 ? value : 150000.0;
    }

    public static boolean autoBuff() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_BUFF;
    }

    /** Parses the comma-separated {@code LLM_AGENT_AUTO_BUFF_SKILLS} list. */
    public static int[] autoBuffSkills() {
        String raw = YamlConfig.config.server.LLM_AGENT_AUTO_BUFF_SKILLS;
        if (raw == null || raw.isBlank()) {
            return new int[0];
        }
        String[] parts = raw.split("[,\\s]+");
        int[] skills = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            try {
                int id = Integer.parseInt(part.trim());
                if (id > 0) {
                    skills[count++] = id;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[count];
        System.arraycopy(skills, 0, result, 0, count);
        return result;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
