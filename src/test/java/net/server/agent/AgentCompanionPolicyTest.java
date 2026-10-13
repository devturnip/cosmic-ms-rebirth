package net.server.agent;

import client.Job;
import config.YamlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCompanionPolicyTest {

    private static void withLevelMatch(String value, Runnable body) {
        String original = YamlConfig.config.server.LLM_AGENT_LEVEL_MATCH;
        try {
            YamlConfig.config.server.LLM_AGENT_LEVEL_MATCH = value;
            body.run();
        } finally {
            YamlConfig.config.server.LLM_AGENT_LEVEL_MATCH = original;
        }
    }

    @Test
    void warriorIsComplementedByHealer() {
        assertEquals(AgentRole.HEALER, AgentCompanionService.complementRole(Job.WARRIOR));
        assertEquals(AgentRole.HEALER, AgentCompanionService.complementRole(Job.DARKKNIGHT));
    }

    @Test
    void damageJobsAreComplementedByWarrior() {
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRole(Job.MAGICIAN));
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRole(Job.CLERIC));
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRole(Job.BOWMAN));
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRole(Job.THIEF));
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRole(Job.PIRATE));
    }

    @Test
    void roleAliasesResolve() {
        assertEquals(AgentRole.MAGICIAN, AgentRole.fromName("mage"));
        assertEquals(AgentRole.BOWMAN, AgentRole.fromName("archer"));
        assertEquals(AgentRole.HEALER, AgentRole.fromName("cleric"));
        assertEquals(AgentRole.PIRATE, AgentRole.fromName("gunslinger"));
        org.junit.jupiter.api.Assertions.assertNull(AgentRole.fromName("nonsense"));
    }

    @Test
    void exactLevelMatchReturnsTheSameLevel() {
        withLevelMatch("EXACT", () -> assertEquals(37, AgentCompanionService.matchLevel(37)));
    }

    @Test
    void band5RoundsDownDeterministically() {
        withLevelMatch("BAND5", () -> {
            assertEquals(35, AgentCompanionService.matchLevel(37));
            assertEquals(35, AgentCompanionService.matchLevel(35));
            assertEquals(40, AgentCompanionService.matchLevel(44));
        });
    }

    @Test
    void levelMatchNeverReturnsBelowOne() {
        withLevelMatch("BAND5", () -> assertEquals(1, AgentCompanionService.matchLevel(3)));
        withLevelMatch("EXACT", () -> assertEquals(1, AgentCompanionService.matchLevel(0)));
    }
}
