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
        return envOrConfig("LLM_API_URL", YamlConfig.config.server.LLM_API_URL);
    }

    public static String apiKey() {
        return envOrConfig("LLM_API_KEY", YamlConfig.config.server.LLM_API_KEY);
    }

    public static String model() {
        return envOrConfig("LLM_MODEL", YamlConfig.config.server.LLM_MODEL);
    }

    /**
     * Environment variables override {@code config.yaml} for secrets, so API keys never need to be
     * written to a tracked file. A blank/missing env var falls through to the config value.
     */
    private static String envOrConfig(String envName, String configValue) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return orEmpty(configValue);
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

    public static int attackSkill() {
        return Math.max(0, YamlConfig.config.server.LLM_AGENT_ATTACK_SKILL);
    }

    public static boolean autoHeal() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_HEAL;
    }

    public static int autoHealSkill() {
        return Math.max(0, YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL);
    }

    public static int healPercent() {
        int value = YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT;
        return value > 0 && value <= 100 ? value : 90;
    }

    public static boolean autoLoot() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_LOOT;
    }

    public static boolean aoeAttack() {
        return YamlConfig.config.server.LLM_AGENT_AOE_ATTACK;
    }

    public static int aoeMaxTargets() {
        int value = YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS;
        return value > 0 ? Math.min(value, 15) : 6;
    }

    public static boolean followWarp() {
        return YamlConfig.config.server.LLM_AGENT_FOLLOW_WARP;
    }

    public static boolean autoRecharge() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_RECHARGE;
    }

    public static double rechargeRangeSq() {
        double value = YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ;
        return value > 0 ? value : 150000.0;
    }

    public static boolean autoFlee() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_FLEE;
    }

    public static int fleePercent() {
        int value = YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT;
        return value > 0 && value <= 100 ? value : 25;
    }

    public static boolean followChannel() {
        return YamlConfig.config.server.LLM_AGENT_FOLLOW_CHANNEL;
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

    // ------------------------------------------------------------------
    // proactive behaviour / cost controls
    // ------------------------------------------------------------------

    public static boolean proactive() {
        return YamlConfig.config.server.LLM_AGENT_PROACTIVE;
    }

    public static boolean greet() {
        return proactive() && YamlConfig.config.server.LLM_AGENT_GREET;
    }

    public static boolean matchmake() {
        return proactive() && YamlConfig.config.server.LLM_AGENT_MATCHMAKE;
    }

    public static boolean patrol() {
        return proactive() && YamlConfig.config.server.LLM_AGENT_PATROL;
    }

    public static long greetCooldownMs() {
        long value = YamlConfig.config.server.LLM_AGENT_GREET_COOLDOWN_MS;
        return value > 0 ? value : 600000L;
    }

    public static long matchmakeIntervalMs() {
        long value = YamlConfig.config.server.LLM_AGENT_MATCHMAKE_INTERVAL_MS;
        return value > 0 ? value : 60000L;
    }

    public static long patrolIntervalMs() {
        long value = YamlConfig.config.server.LLM_AGENT_PATROL_INTERVAL_MS;
        return value > 0 ? value : 8000L;
    }

    public static String greetingTemplate() {
        return orEmpty(YamlConfig.config.server.LLM_AGENT_GREETING);
    }

    public static boolean memorySummarize() {
        return YamlConfig.config.server.LLM_AGENT_MEMORY_SUMMARIZE;
    }

    /** Global cap on LLM requests per minute; 0 means unlimited. */
    public static int maxCallsPerMinute() {
        return Math.max(0, YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN);
    }

    public static boolean notice() {
        return YamlConfig.config.server.LLM_AGENT_NOTICE;
    }

    // ------------------------------------------------------------------
    // player-facing companions
    // ------------------------------------------------------------------

    /** Whether players may summon ephemeral companions (requires the master switch too). */
    public static boolean playerSpawn() {
        return enabled() && YamlConfig.config.server.LLM_AGENT_PLAYER_SPAWN;
    }

    public static int maxPerPlayer() {
        int value = YamlConfig.config.server.LLM_AGENT_MAX_PER_PLAYER;
        return value > 0 ? value : 1;
    }

    public static long requestCooldownMs() {
        long value = YamlConfig.config.server.LLM_AGENT_REQUEST_COOLDOWN_MS;
        return value > 0 ? value : 60000L;
    }

    /** Level matching policy: {@code EXACT} or {@code BAND5}. */
    public static String levelMatch() {
        String value = YamlConfig.config.server.LLM_AGENT_LEVEL_MATCH;
        return value == null || value.isBlank() ? "EXACT" : value.toUpperCase();
    }

    public static boolean autoParty() {
        return YamlConfig.config.server.LLM_AGENT_AUTO_PARTY;
    }

    public static boolean complementJob() {
        return YamlConfig.config.server.LLM_AGENT_COMPLEMENT_JOB;
    }

    public static String accountPrefix() {
        String value = YamlConfig.config.server.LLM_AGENT_ACCOUNT_PREFIX;
        return value == null || value.isBlank() ? "agnt" : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
