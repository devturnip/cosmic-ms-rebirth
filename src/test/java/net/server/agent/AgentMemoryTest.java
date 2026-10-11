package net.server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryTest {

    @Test
    void rememberFactStoresFactAndCollapsesDuplicates() {
        AgentMemory memory = new AgentMemory(1);

        memory.rememberFact("likes apples");
        memory.rememberFact("likes apples");
        memory.rememberFact("plays MapleStory");

        assertEquals(List.of("likes apples", "plays MapleStory"), memory.facts());
    }

    @Test
    void recentDialogueKeepsAtMostTwentyFourEntriesDroppingOldest() {
        AgentMemory memory = new AgentMemory(1);

        for (int i = 0; i < 30; i++) {
            memory.rememberDialogue("line-" + i);
        }

        List<String> dialogue = memory.recentDialogue();
        assertEquals(24, dialogue.size());
        assertEquals("line-6", dialogue.get(0));
        assertEquals("line-29", dialogue.get(23));
    }

    @Test
    void digestContainsFactsAndDialogue() {
        AgentMemory memory = new AgentMemory(1);

        memory.rememberFact("favourite colour is blue");
        memory.rememberDialogue("hello there");

        String digest = memory.digest();
        assertTrue(digest.contains("favourite colour is blue"));
        assertTrue(digest.contains("hello there"));
    }

    @Test
    void replaceFactsOverwritesPriorContent() {
        AgentMemory memory = new AgentMemory(1);

        memory.rememberFact("old fact");
        memory.replaceFacts(List.of("new fact"));

        assertEquals(List.of("new fact"), memory.facts());
    }

    @Test
    void replaceDialogueOverwritesPriorContent() {
        AgentMemory memory = new AgentMemory(1);

        memory.rememberDialogue("old line");
        memory.replaceDialogue(List.of("new line"));

        assertEquals(List.of("new line"), memory.recentDialogue());
    }
}
