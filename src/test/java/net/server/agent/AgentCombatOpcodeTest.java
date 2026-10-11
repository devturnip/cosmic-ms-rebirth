package net.server.agent;

import client.Character;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies opcode selection and the skill-use guard for {@link AgentCombat}. */
class AgentCombatOpcodeTest {

    @Test
    void basicAttackOpcodeIsCloseRangeAttack() {
        // A skill-0 magic packet would NPE MagicDamageHandler (SkillFactory.getSkill(0) is null),
        // so basic attacks must always use the close-range handler.
        assertEquals(RecvOpcode.CLOSE_RANGE_ATTACK, AgentCombat.basicAttackOpcode());
    }

    @Test
    void canUseAttackSkillIsFalseForNonPositiveSkillWithoutCharacterLookups() {
        Character chr = Mockito.mock(Character.class);

        // Guard clause short-circuits before any SkillFactory/WZ access.
        assertFalse(AgentCombat.canUseAttackSkill(chr, 0));
        verifyNoInteractions(chr);
    }
}
