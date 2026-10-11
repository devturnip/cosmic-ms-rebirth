package net.server.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-agent memory: a bounded rolling window of recent dialogue plus a set of durable
 * facts. Persistence is delegated to {@link AgentMemoryStore} (see {@code AgentMemoryStore}).
 */
public class AgentMemory {
    private static final int MAX_DIALOGUE = 24;
    private static final int MAX_FACTS = 128;

    private final int characterId;
    private final Deque<String> recentDialogue = new ArrayDeque<>();
    private final Set<String> facts = new LinkedHashSet<>();

    public AgentMemory(int characterId) {
        this.characterId = characterId;
    }

    public int getCharacterId() {
        return characterId;
    }

    public synchronized void rememberDialogue(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        recentDialogue.addLast(line);
        while (recentDialogue.size() > MAX_DIALOGUE) {
            recentDialogue.removeFirst();
        }
    }

    public synchronized void rememberFact(String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        facts.add(fact.trim());
        while (facts.size() > MAX_FACTS) {
            var iterator = facts.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized List<String> recentDialogue() {
        return new ArrayList<>(recentDialogue);
    }

    public synchronized List<String> facts() {
        return new ArrayList<>(facts);
    }

    /** A compact textual digest suitable for inclusion in an LLM prompt. */
    public synchronized String digest() {
        StringBuilder sb = new StringBuilder();
        if (!facts.isEmpty()) {
            sb.append("Known facts:\n");
            for (String fact : facts) {
                sb.append("- ").append(fact).append('\n');
            }
        }
        if (!recentDialogue.isEmpty()) {
            sb.append("Recent conversation:\n");
            for (String line : recentDialogue) {
                sb.append("- ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized void replaceFacts(Iterable<String> loaded) {
        facts.clear();
        for (String fact : loaded) {
            facts.add(fact);
        }
    }

    public synchronized void replaceDialogue(Iterable<String> loaded) {
        recentDialogue.clear();
        for (String line : loaded) {
            recentDialogue.addLast(line);
        }
    }
}
