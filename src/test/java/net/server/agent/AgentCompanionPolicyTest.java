package net.server.agent;

import client.Job;
import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void complementsExcludeThePlayersOwnClass() {
        assertEquals(List.of(AgentRole.HEALER, AgentRole.MAGICIAN, AgentRole.BOWMAN,
                        AgentRole.THIEF, AgentRole.PIRATE),
                AgentCompanionService.complementRoles(Job.WARRIOR));
        assertEquals(List.of(AgentRole.HEALER, AgentRole.WARRIOR, AgentRole.MAGICIAN,
                        AgentRole.BOWMAN, AgentRole.THIEF),
                AgentCompanionService.complementRoles(Job.PIRATE));
        assertFalse(AgentCompanionService.complementRoles(Job.WARRIOR).contains(AgentRole.WARRIOR));
    }

    @Test
    void everyOtherClassIsReachableAsAComplement() {
        // The old policy only ever produced warrior/healer; all five other classes must now be possible.
        for (AgentRole player : AgentRole.values()) {
            List<AgentRole> partners = AgentCompanionService.complementRoles(player.job());
            assertEquals(AgentRole.values().length - 1, partners.size(), player + " should have every other role");
            assertFalse(partners.contains(player), player + " must not complement itself");
            for (AgentRole role : AgentRole.values()) {
                if (role != player) {
                    assertTrue(partners.contains(role), player + " should be able to summon " + role);
                }
            }
        }
    }

    @Test
    void healersGetAFrontlinePartnerFirst() {
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRoles(Job.CLERIC).get(0));
        assertEquals(AgentRole.WARRIOR, AgentCompanionService.complementRoles(Job.BISHOP).get(0));
    }

    @Test
    void advancedJobsResolveToTheirBaseRole() {
        assertFalse(AgentCompanionService.complementRoles(Job.DARKKNIGHT).contains(AgentRole.WARRIOR));
        assertFalse(AgentCompanionService.complementRoles(Job.NIGHTLORD).contains(AgentRole.THIEF));
        assertFalse(AgentCompanionService.complementRoles(Job.BISHOP).contains(AgentRole.HEALER));
    }

    @Test
    void weightedPickFavoursTheBestPartnerButKeepsEveryClassReachable() {
        List<AgentRole> ordered = AgentCompanionService.complementRoles(Job.WARRIOR); // healer first
        Random random = new Random(20241014L);
        EnumSet<AgentRole> seen = EnumSet.noneOf(AgentRole.class);
        int healers = 0;
        final int draws = 6000;
        for (int i = 0; i < draws; i++) {
            AgentRole picked = AgentRole.pickComplement(ordered, random);
            seen.add(picked);
            if (picked == AgentRole.HEALER) {
                healers++;
            }
        }
        assertTrue(seen.containsAll(ordered), "every eligible class should be drawn at least once");
        assertFalse(seen.contains(AgentRole.WARRIOR), "the player's own class must never be drawn");
        // Best partner is 2/6 of the weight; assert it leads but is not dominant.
        assertTrue(healers > draws / 5 && healers < draws / 2, "healer share was " + healers + "/" + draws);
    }

    @Test
    void pickComplementHandlesAnEmptyPool() {
        assertNull(AgentRole.pickComplement(List.of(), new Random(1L)));
        assertNull(AgentRole.pickComplement(null, new Random(1L)));
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
