package net.server.agent;

import client.Job;
import client.inventory.WeaponType;
import constants.skills.Cleric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link CompanionTemplate}. Weapon selection and the real damaging-skill
 * predicate need WZ data, so those live in {@link CompanionTemplateWzTest}; these tests cover the
 * DB/WZ-free logic only.
 */
class CompanionTemplateTest {

    private static final List<Integer> SAMPLE_LEVELS = List.of(1, 10, 30, 70, 120, 200);
    private static final List<Integer> JOB_TIER_LEVELS = List.of(10, 30, 70, 120);

    private static void assertJob(AgentRole role, int level, Job expected) {
        assertEquals(expected, CompanionTemplate.jobFor(role, level),
                role + " at level " + level + " should be " + expected);
    }

    @Test
    void jobProgressionFollowsTheStandardTierBoundaries() {
        assertJob(AgentRole.WARRIOR, 9, Job.BEGINNER);
        assertJob(AgentRole.WARRIOR, 10, Job.WARRIOR);
        assertJob(AgentRole.WARRIOR, 30, Job.FIGHTER);
        assertJob(AgentRole.WARRIOR, 70, Job.CRUSADER);
        assertJob(AgentRole.WARRIOR, 120, Job.HERO);

        assertJob(AgentRole.MAGICIAN, 9, Job.BEGINNER);
        assertJob(AgentRole.MAGICIAN, 10, Job.MAGICIAN);
        assertJob(AgentRole.MAGICIAN, 30, Job.FP_WIZARD);
        assertJob(AgentRole.MAGICIAN, 70, Job.FP_MAGE);
        assertJob(AgentRole.MAGICIAN, 120, Job.FP_ARCHMAGE);

        assertJob(AgentRole.HEALER, 9, Job.BEGINNER);
        assertJob(AgentRole.HEALER, 10, Job.MAGICIAN);
        assertJob(AgentRole.HEALER, 30, Job.CLERIC);
        assertJob(AgentRole.HEALER, 70, Job.PRIEST);
        assertJob(AgentRole.HEALER, 120, Job.BISHOP);

        assertJob(AgentRole.BOWMAN, 9, Job.BEGINNER);
        assertJob(AgentRole.BOWMAN, 10, Job.BOWMAN);
        assertJob(AgentRole.BOWMAN, 30, Job.HUNTER);
        assertJob(AgentRole.BOWMAN, 70, Job.RANGER);
        assertJob(AgentRole.BOWMAN, 120, Job.BOWMASTER);

        assertJob(AgentRole.THIEF, 9, Job.BEGINNER);
        assertJob(AgentRole.THIEF, 10, Job.THIEF);
        assertJob(AgentRole.THIEF, 30, Job.ASSASSIN);
        assertJob(AgentRole.THIEF, 70, Job.HERMIT);
        assertJob(AgentRole.THIEF, 120, Job.NIGHTLORD);

        assertJob(AgentRole.PIRATE, 9, Job.BEGINNER);
        assertJob(AgentRole.PIRATE, 10, Job.PIRATE);
        assertJob(AgentRole.PIRATE, 30, Job.GUNSLINGER);
        assertJob(AgentRole.PIRATE, 70, Job.OUTLAW);
        assertJob(AgentRole.PIRATE, 120, Job.CORSAIR);
    }

    @Test
    void buildForResolvesTheAdvancedJobFromAFirstJob() {
        assertEquals(Job.CRUSADER, CompanionTemplate.buildFor(Job.WARRIOR, 80).job());
        assertEquals(Job.PRIEST, CompanionTemplate.buildFor(Job.CLERIC, 80).job());
        assertEquals(Job.BISHOP, CompanionTemplate.buildFor(Job.CLERIC, 130).job());
        assertEquals(Job.BOWMASTER, CompanionTemplate.buildFor(Job.BOWMAN, 130).job());
        assertEquals(Job.NIGHTLORD, CompanionTemplate.buildFor(Job.THIEF, 130).job());
        assertEquals(Job.CORSAIR, CompanionTemplate.buildFor(Job.PIRATE, 130).job());
    }

    @Test
    void everyApPointIsSpentAndStatsStayValid() {
        for (AgentRole role : AgentRole.values()) {
            for (int level : SAMPLE_LEVELS) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                int expectedTotal = 16 + Math.max(0, level - 1) * 5;
                assertEquals(0, build.remainingAp(), role + " level " + level + " left AP unspent");
                assertEquals(expectedTotal, build.totalStats(),
                        role + " level " + level + " must account for every AP point");
                assertTrue(build.str() >= 4 && build.dex() >= 4 && build.int_() >= 4 && build.luk() >= 4,
                        role + " level " + level + " produced a stat below the 4-point base");
            }
        }
    }

    @Test
    void healerLearnsHealAtEveryTier() {
        for (int level : List.of(30, 70, 120)) {
            CompanionTemplate.Build build = CompanionTemplate.build(AgentRole.HEALER, level);
            assertTrue(build.skills().stream()
                            .anyMatch(grant -> grant.skillId() == Cleric.HEAL && grant.level() > 0),
                    "healer level " + level + " must learn Heal so autoHeal works: " + skillIds(build));
        }
    }

    @Test
    void skillLevelsArePositiveAndWithinKnownCaps() {
        for (AgentRole role : AgentRole.values()) {
            for (int level : JOB_TIER_LEVELS) {
                for (CompanionTemplate.SkillGrant grant : CompanionTemplate.build(role, level).skills()) {
                    assertTrue(grant.level() >= 1 && grant.level() <= 30,
                            role + " level " + level + " granted an out-of-range skill level " + grant.level()
                                    + " for skill " + grant.skillId());
                }
            }
        }
    }

    @Test
    void spBudgetIsNeverOverspent() {
        for (AgentRole role : AgentRole.values()) {
            for (int level : SAMPLE_LEVELS) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                assertTrue(build.remainingSp() >= 0, role + " level " + level + " overspent SP");
                int spent = build.skills().stream().mapToInt(CompanionTemplate.SkillGrant::level).sum();
                int totalSp = level >= 10 ? (level - 9) * 3 : 0;
                assertEquals(totalSp, spent + build.remainingSp(),
                        role + " level " + level + " SP does not add up");
            }
        }
    }

    @Test
    void healthAndManaArePositiveAndGrowWithLevel() {
        for (AgentRole role : AgentRole.values()) {
            int previousHp = -1;
            int previousMp = -1;
            for (int level = 1; level <= 140; level++) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                assertTrue(build.maxHp() > previousHp, role + " maxHp did not grow at level " + level);
                assertTrue(build.maxMp() > previousMp, role + " maxMp did not grow at level " + level);
                assertTrue(build.maxHp() > 0 && build.maxMp() > 0, role + " produced non-positive HP/MP");
                previousHp = build.maxHp();
                previousMp = build.maxMp();
            }
        }
    }

    @Test
    void everyRoleHasUsableWeaponTypes() {
        assertFalse(CompanionTemplate.roleWeaponTypes(AgentRole.WARRIOR, 10).isEmpty());
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.WARRIOR, 10).contains(WeaponType.SWORD1H));
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.WARRIOR, 120).contains(WeaponType.SWORD2H));

        for (AgentRole role : List.of(AgentRole.MAGICIAN, AgentRole.HEALER)) {
            assertTrue(CompanionTemplate.roleWeaponTypes(role, 120).contains(WeaponType.WAND));
            assertTrue(CompanionTemplate.roleWeaponTypes(role, 120).contains(WeaponType.STAFF));
        }
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.BOWMAN, 120).contains(WeaponType.BOW));
        // The thief is a dagger rogue at tier 1 and a claw assassin from level 30 on.
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.THIEF, 10).contains(WeaponType.DAGGER_OTHER));
        assertFalse(CompanionTemplate.roleWeaponTypes(AgentRole.THIEF, 10).contains(WeaponType.CLAW));
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.THIEF, 30).contains(WeaponType.CLAW));
        assertTrue(CompanionTemplate.roleWeaponTypes(AgentRole.PIRATE, 120).contains(WeaponType.GUN));

        for (AgentRole role : AgentRole.values()) {
            for (int level : JOB_TIER_LEVELS) {
                CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                assertFalse(CompanionTemplate.roleWeaponTypes(build.role(), build.level()).isEmpty(),
                        role + " level " + level + " has no weapon type");
            }
        }
    }

    @Test
    void unknownRolesGetABareBeginnerBuild() {
        CompanionTemplate.Build build = CompanionTemplate.build(null, 50);
        assertEquals(Job.BEGINNER, build.job());
        assertTrue(build.skills().isEmpty());
        // An unrecognised job still accounts for the level's AP/SP rather than discarding them.
        assertEquals(16 + (50 - 1) * 5 - 16, build.remainingAp());
        assertEquals((50 - 9) * 3, build.remainingSp());
    }

    private static String skillIds(CompanionTemplate.Build build) {
        return build.skills().stream()
                .map(grant -> grant.skillId() + "@" + grant.level())
                .collect(Collectors.joining(", "));
    }
}
