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
