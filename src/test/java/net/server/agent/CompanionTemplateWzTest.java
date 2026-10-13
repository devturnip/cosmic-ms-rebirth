package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Warrior;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WZ-backed checks for {@link CompanionTemplate}: every granted skill id must resolve against the
 * real {@code Skill.wz} data and fit its max level, and every role/tier must grant at least one
 * skill {@link AgentCombat#isAttackSkill} accepts. These assertions catch stale constants and
 * over-requested skill levels that the DB/WZ-free {@link CompanionTemplateTest} cannot see.
 * <p>
 * Skips gracefully when the WZ data is unavailable (even a known skill resolves {@code null}).
 */
class CompanionTemplateWzTest {

    private static final List<Integer> TIER_LEVELS = List.of(10, 30, 70, 120);

    @BeforeAll
    static void loadSkillData() {
        try {
            SkillFactory.loadAllSkills();
        } catch (Throwable ignored) {
            // WZ unavailable; the assumption in each test skips it.
        }
    }

    @Test
    void everyGrantedSkillResolvesAndFitsItsWzMaxLevel() {
        assumeTrue(SkillFactory.getSkill(Warrior.POWER_STRIKE) != null, "WZ skill data unavailable");
        for (AgentRole role : AgentRole.values()) {
            for (int level : TIER_LEVELS) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                for (CompanionTemplate.SkillGrant grant : build.skills()) {
                    Skill skill = SkillFactory.getSkill(grant.skillId());
                    assertNotNull(skill, role + " level " + level + " granted unknown skill " + grant.skillId());
                    assertTrue(grant.level() >= 1 && grant.level() <= skill.getMaxLevel(),
                            role + " level " + level + " granted " + grant.skillId() + "@" + grant.level()
                                    + " outside WZ max " + skill.getMaxLevel());
                }
            }
        }
    }

    @Test
    void everyRoleTierGrantsAnAttackSkill() {
        assumeTrue(SkillFactory.getSkill(Warrior.POWER_STRIKE) != null, "WZ skill data unavailable");
        for (AgentRole role : AgentRole.values()) {
            for (int level : TIER_LEVELS) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                Character chr = mock(Character.class);
                for (CompanionTemplate.SkillGrant grant : build.skills()) {
                    Skill skill = SkillFactory.getSkill(grant.skillId());
                    if (skill != null) {
                        final int skillLevel = Math.min(grant.level(), skill.getMaxLevel());
                        when(chr.getSkillLevel(grant.skillId())).thenReturn(skillLevel);
                        when(chr.getSkillLevel(skill)).thenReturn((byte) skillLevel);
                    }
                }
                assertTrue(build.skills().stream()
                                .map(CompanionTemplate.SkillGrant::skillId)
                                .anyMatch(id -> AgentCombat.isAttackSkill(chr, id)),
                        role + " level " + level + " has no accepted attack skill: " + describe(build));
            }
        }
    }

    private static String describe(CompanionTemplate.Build build) {
        return build.skills().stream()
                .map(grant -> grant.skillId() + "@" + grant.level())
                .collect(Collectors.joining(", "));
    }
}
