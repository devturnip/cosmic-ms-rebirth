package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryCompactTest {

    @Test
    void compactFoldsOldDialogueIntoAFact() {
        AgentMemory memory = new AgentMemory(1);
        for (int i = 0; i < 20; i++) {
            memory.rememberDialogue("line " + i);
        }

        assertTrue(memory.compact(), "dialogue past the threshold should be compacted");
        assertTrue(memory.recentDialogue().size() <= 16, "compaction should shrink the dialogue window");
        assertFalse(memory.facts().isEmpty(), "compaction should leave a durable summary fact");
        assertTrue(memory.facts().iterator().next().startsWith("Earlier conversation:"),
                "summary fact should be labelled");
    }

    @Test
    void compactIsANoOpBelowTheThreshold() {
        AgentMemory memory = new AgentMemory(1);
        memory.rememberDialogue("hello");

        assertFalse(memory.compact());
        assertTrue(memory.facts().isEmpty());
    }
}
