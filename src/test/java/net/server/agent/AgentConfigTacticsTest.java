package net.server.agent;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTacticsTest {

    @Test
    void healPercentFallsBackForOutOfRangeValues() {
        int original = YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT;
        try {
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = 200;
            assertEquals(90, AgentConfig.healPercent());
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = 0;
            assertEquals(90, AgentConfig.healPercent());
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = 65;
            assertEquals(65, AgentConfig.healPercent());
        } finally {
            YamlConfig.config.server.LLM_AGENT_HEAL_PERCENT = original;
        }
    }

    @Test
    void aoeMaxTargetsClampsToSaneRange() {
        int original = YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS;
        try {
            YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS = 0;
            assertEquals(6, AgentConfig.aoeMaxTargets());
            YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS = 100;
            assertEquals(15, AgentConfig.aoeMaxTargets());
            YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS = 3;
            assertEquals(3, AgentConfig.aoeMaxTargets());
        } finally {
            YamlConfig.config.server.LLM_AGENT_AOE_MAX_TARGETS = original;
        }
    }

    @Test
    void autoRechargeReflectsConfig() {
        boolean original = YamlConfig.config.server.LLM_AGENT_AUTO_RECHARGE;
        try {
            YamlConfig.config.server.LLM_AGENT_AUTO_RECHARGE = true;
            assertTrue(AgentConfig.autoRecharge());
            YamlConfig.config.server.LLM_AGENT_AUTO_RECHARGE = false;
            assertFalse(AgentConfig.autoRecharge());
        } finally {
            YamlConfig.config.server.LLM_AGENT_AUTO_RECHARGE = original;
        }
    }

    @Test
    void rechargeRangeSqFallsBackForNonPositiveValues() {
        double original = YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ;
        try {
            YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ = 0;
            assertEquals(150000.0, AgentConfig.rechargeRangeSq());
            YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ = -1;
            assertEquals(150000.0, AgentConfig.rechargeRangeSq());
            YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ = 40000.0;
            assertEquals(40000.0, AgentConfig.rechargeRangeSq());
        } finally {
            YamlConfig.config.server.LLM_AGENT_RECHARGE_RANGE_SQ = original;
        }
    }

    @Test
    void fleePercentFallsBackForOutOfRangeValues() {
        int original = YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT;
        try {
            YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT = 0;
            assertEquals(25, AgentConfig.fleePercent());
            YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT = 150;
            assertEquals(25, AgentConfig.fleePercent());
            YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT = 40;
            assertEquals(40, AgentConfig.fleePercent());
        } finally {
            YamlConfig.config.server.LLM_AGENT_FLEE_PERCENT = original;
        }
    }

    @Test
    void fleeAndFollowChannelReflectConfig() {
        boolean originalFlee = YamlConfig.config.server.LLM_AGENT_AUTO_FLEE;
        boolean originalFollow = YamlConfig.config.server.LLM_AGENT_FOLLOW_CHANNEL;
        try {
            YamlConfig.config.server.LLM_AGENT_AUTO_FLEE = true;
            YamlConfig.config.server.LLM_AGENT_FOLLOW_CHANNEL = true;
            assertTrue(AgentConfig.autoFlee());
            assertTrue(AgentConfig.followChannel());
            YamlConfig.config.server.LLM_AGENT_AUTO_FLEE = false;
            YamlConfig.config.server.LLM_AGENT_FOLLOW_CHANNEL = false;
            assertFalse(AgentConfig.autoFlee());
            assertFalse(AgentConfig.followChannel());
        } finally {
            YamlConfig.config.server.LLM_AGENT_AUTO_FLEE = originalFlee;
            YamlConfig.config.server.LLM_AGENT_FOLLOW_CHANNEL = originalFollow;
        }
    }

    @Test
    void proactiveSubSwitchesRequireTheMasterSwitch() {
        boolean originalMaster = YamlConfig.config.server.LLM_AGENT_PROACTIVE;
        boolean originalGreet = YamlConfig.config.server.LLM_AGENT_GREET;
        boolean originalMatch = YamlConfig.config.server.LLM_AGENT_MATCHMAKE;
        boolean originalPatrol = YamlConfig.config.server.LLM_AGENT_PATROL;
        try {
            YamlConfig.config.server.LLM_AGENT_PROACTIVE = false;
            YamlConfig.config.server.LLM_AGENT_GREET = true;
            YamlConfig.config.server.LLM_AGENT_MATCHMAKE = true;
            YamlConfig.config.server.LLM_AGENT_PATROL = true;
            assertFalse(AgentConfig.greet());
            assertFalse(AgentConfig.matchmake());
            assertFalse(AgentConfig.patrol());

            YamlConfig.config.server.LLM_AGENT_PROACTIVE = true;
            assertTrue(AgentConfig.greet());
            assertTrue(AgentConfig.matchmake());
            assertTrue(AgentConfig.patrol());
        } finally {
            YamlConfig.config.server.LLM_AGENT_PROACTIVE = originalMaster;
            YamlConfig.config.server.LLM_AGENT_GREET = originalGreet;
            YamlConfig.config.server.LLM_AGENT_MATCHMAKE = originalMatch;
            YamlConfig.config.server.LLM_AGENT_PATROL = originalPatrol;
        }
    }

    @Test
    void proactiveIntervalsFallBackForNonPositiveValues() {
        long originalGreet = YamlConfig.config.server.LLM_AGENT_GREET_COOLDOWN_MS;
        long originalMatch = YamlConfig.config.server.LLM_AGENT_MATCHMAKE_INTERVAL_MS;
        long originalPatrol = YamlConfig.config.server.LLM_AGENT_PATROL_INTERVAL_MS;
        try {
            YamlConfig.config.server.LLM_AGENT_GREET_COOLDOWN_MS = 0;
            YamlConfig.config.server.LLM_AGENT_MATCHMAKE_INTERVAL_MS = -1;
            YamlConfig.config.server.LLM_AGENT_PATROL_INTERVAL_MS = 0;
            assertEquals(600000L, AgentConfig.greetCooldownMs());
            assertEquals(60000L, AgentConfig.matchmakeIntervalMs());
            assertEquals(8000L, AgentConfig.patrolIntervalMs());
        } finally {
            YamlConfig.config.server.LLM_AGENT_GREET_COOLDOWN_MS = originalGreet;
            YamlConfig.config.server.LLM_AGENT_MATCHMAKE_INTERVAL_MS = originalMatch;
            YamlConfig.config.server.LLM_AGENT_PATROL_INTERVAL_MS = originalPatrol;
        }
    }

    @Test
    void maxCallsPerMinuteIsNeverNegative() {
        int original = YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN;
        try {
            YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = -5;
            assertEquals(0, AgentConfig.maxCallsPerMinute());
            YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = 120;
            assertEquals(120, AgentConfig.maxCallsPerMinute());
        } finally {
            YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = original;
        }
    }

    @Test
    void autoHealSkillIsNeverNegative() {
        int original = YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL;
        try {
            YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = -5;
            assertEquals(0, AgentConfig.autoHealSkill());
            YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = 2301002;
            assertEquals(2301002, AgentConfig.autoHealSkill());
        } finally {
            YamlConfig.config.server.LLM_AGENT_AUTO_HEAL_SKILL = original;
        }
    }
}
