package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WZ-backed checks for {@link AgentCombat#selectAttackSkill(AgentSession)}: a companion must
 * automatically use one of its <em>own</em> class attack skills (not a single global id, and not
 * always a basic attack), the explicit {@code LLM_AGENT_ATTACK_SKILL} override still wins, and an
 * agent with no learned attack skill falls back to skill 0 (basic attack).
 * <p>
 * Skips gracefully when the WZ data is unavailable, mirroring {@code CompanionTemplateWzTest}.
 */
class AgentCombatSkillSelectionTest {

    private static final int PROVIDER_PROBE_SKILL = 1000000; // Improved HP Recovery

    @BeforeAll
    static void loadSkillData() {
        try {
            SkillFactory.loadAllSkills();
        } catch (Throwable ignored) {
            // WZ unavailable; the assumption in each test skips it.
        }
    }

    @Test
    void autoSelectsALearnedClassAttackSkillForEveryRole() {
        assumeTrue(SkillFactory.getSkill(PROVIDER_PROBE_SKILL) != null, "WZ skill data unavailable");
        withConfiguredSkill(0, () -> {
            for (AgentRole role : AgentRole.values()) {
                for (int level : List.of(30, 70, 120)) {
                    CompanionTemplate.Build build = CompanionTemplate.build(role, level);
                    Character chr = mockCompanion(build);
                    AgentSession session = sessionFor(chr);

                    int skillId = AgentCombat.selectAttackSkill(session);

                    assertTrue(skillId > 0, role + "@" + level + " auto-selected no skill");
                    assertTrue(AgentCombat.isAttackSkill(chr, skillId),
                            role + "@" + level + " selected a non-attack skill " + skillId);
                    assertTrue(build.skills().stream().anyMatch(g -> g.skillId() == skillId),
                            role + "@" + level + " selected " + skillId + " which the template never granted");
                }
            }
        });
    }

    @Test
    void configuredAttackSkillOverridesAutoSelection() {
        assumeTrue(SkillFactory.getSkill(PROVIDER_PROBE_SKILL) != null, "WZ skill data unavailable");
        CompanionTemplate.Build build = CompanionTemplate.build(AgentRole.WARRIOR, 70);
        Character chr = mockCompanion(build);
        AgentSession session = sessionFor(chr);
        int configured = build.skills().stream()
                .map(CompanionTemplate.SkillGrant::skillId)
                .filter(id -> AgentCombat.isAttackSkill(chr, id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("warrior build has no attack skill"));

        withConfiguredSkill(configured, () ->
                assertEquals(configured, AgentCombat.selectAttackSkill(session),
                        "the explicitly configured skill must win"));
    }

    @Test
    void fallsBackToBasicAttackWhenNoAttackSkillIsLearned() {
        Character chr = mock(Character.class);
        when(chr.getSkills()).thenReturn(new HashMap<>());
        AgentSession session = sessionFor(chr);

        withConfiguredSkill(0, () ->
                assertEquals(0, AgentCombat.selectAttackSkill(session), "no skill means basic attack"));
    }

    /** Runs {@code body} with the global attack-skill config pinned, restoring it afterwards. */
    private static void withConfiguredSkill(int skillId, Runnable body) {
        int saved = YamlConfig.config.server.LLM_AGENT_ATTACK_SKILL;
        YamlConfig.config.server.LLM_AGENT_ATTACK_SKILL = skillId;
        try {
            body.run();
        } finally {
            YamlConfig.config.server.LLM_AGENT_ATTACK_SKILL = saved;
        }
    }

    private static AgentSession sessionFor(Character chr) {
        AgentSession session = mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        when(session.isSkillReady(Mockito.anyInt())).thenReturn(true);
        return session;
    }

    /** A character that has exactly the template's skills learned, with enough HP/MP to cast. */
    private static Character mockCompanion(CompanionTemplate.Build build) {
        Character chr = mock(Character.class);
        Map<Skill, Character.SkillEntry> skills = new HashMap<>();
        for (CompanionTemplate.SkillGrant grant : build.skills()) {
            Skill skill = SkillFactory.getSkill(grant.skillId());
            if (skill == null) {
                continue;
            }
            int level = Math.min(grant.level(), skill.getMaxLevel());
            skills.put(skill, null); // only the key set is read by selectAttackSkill
            when(chr.getSkillLevel(grant.skillId())).thenReturn(level);
            when(chr.getSkillLevel(skill)).thenReturn((byte) level);
        }
        when(chr.getSkills()).thenReturn(skills);
        when(chr.getHp()).thenReturn(100_000);
        when(chr.getMp()).thenReturn(100_000);
        when(chr.getTotalMagic()).thenReturn(1_000);
        when(chr.getTotalInt()).thenReturn(500);
        when(chr.getTotalWatk()).thenReturn(200);
        when(chr.calculateMaxBaseDamage(Mockito.anyInt())).thenReturn(1_000);
        when(chr.skillIsCooling(Mockito.anyInt())).thenReturn(false);
        return chr;
    }
}
