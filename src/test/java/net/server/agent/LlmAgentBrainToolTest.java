package net.server.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the LLM tool -> {@link AgentAction} mapping, without any network calls (the
 * {@link OpenAiChatClient} is only constructed, never used).
 */
class LlmAgentBrainToolTest {

    private final LlmAgentBrain brain = new LlmAgentBrain("Test");

    private static OpenAiChatClient.ToolCall call(String name, String json) {
        return new OpenAiChatClient.ToolCall(name, json);
    }

    @Test
    void attackWithoutSkillMapsToBasicAttack() {
        AgentAction action = brain.toAction(call("attack", "{\"target_object_id\": 42}"));

        AgentAction.Attack attack = assertInstanceOf(AgentAction.Attack.class, action);
        assertEquals(42, attack.targetObjectId());
    }

    @Test
    void attackWithSkillMapsToSkillAttack() {
        AgentAction action = brain.toAction(call("attack", "{\"target_object_id\": 42, \"skill_id\": 2301002}"));

        AgentAction.SkillAttack attack = assertInstanceOf(AgentAction.SkillAttack.class, action);
        assertEquals(42, attack.targetObjectId());
        assertEquals(2301002, attack.skillId());
    }

    @Test
    void attackManyMapsTargetsAndDropsDuplicates() {
        AgentAction action = brain.toAction(
                call("attack_many", "{\"target_object_ids\": [1, 2, 2, 3], \"skill_id\": 2301002}"));

        AgentAction.SkillAttackMany many = assertInstanceOf(AgentAction.SkillAttackMany.class, action);
        assertEquals(List.of(1, 2, 3), many.targetObjectIds());
        assertEquals(2301002, many.skillId());
    }

    @Test
    void attackManyWithoutTargetsIsIgnored() {
        assertNull(brain.toAction(call("attack_many", "{\"skill_id\": 2301002}")));
        assertNull(brain.toAction(call("attack_many", "{\"target_object_ids\": []}")));
    }

    @Test
    void rechargeAmmoMapsToRechargeAction() {
        assertInstanceOf(AgentAction.RechargeAmmo.class, brain.toAction(call("recharge_ammo", "{}")));
    }

    @Test
    void invalidArgumentsAreIgnored() {
        assertNull(brain.toAction(call("attack_many", "not json")));
    }

    @Test
    void unknownToolIsIgnored() {
        assertNull(brain.toAction(call("teleport", "{}")));
    }

    @Test
    void skillAttackManyCapsTargetsAndCopiesTheList() {
        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            input.add(i);
        }

        AgentAction.SkillAttackMany many = new AgentAction.SkillAttackMany(input, 1);
        input.clear();

        assertEquals(15, many.targetObjectIds().size(), "target list is capped to the packet nibble");
        assertEquals(15, many.targetObjectIds().size(), "defensive copy survives caller mutation");
    }
}
