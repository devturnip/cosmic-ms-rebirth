package net.server.agent;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
