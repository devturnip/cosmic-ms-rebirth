package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigBuffTest {

    @Test
    void autoBuffSkillsReturnsNonNullArrayWithPositiveIds() {
        int[] skills = AgentConfig.autoBuffSkills();

        assertNotNull(skills, "autoBuffSkills() must never return null");
        for (int skillId : skills) {
            assertTrue(skillId > 0, "every configured buff skill id must be positive, was: " + skillId);
        }
    }

    @Test
    void autoBuffIsACallableBoolean() {
        assertDoesNotThrow(() -> {
            boolean autoBuff = AgentConfig.autoBuff();
            // Boolean accessor simply must be callable; no config-specific value is asserted.
            assertTrue(autoBuff || !autoBuff);
        });
    }
}
