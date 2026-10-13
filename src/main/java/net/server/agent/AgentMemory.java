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
    private static final int COMPACT_AFTER = 16;
    private static final int COMPACT_KEEP = 10;
    private static final int MAX_SUMMARY_LENGTH = 240;

    private final int characterId;
    private final Deque<String> recentDialogue = new ArrayDeque<>();
    private final Set<String> facts = new LinkedHashSet<>();
    /** The single rolling "earlier conversation" fact, replaced (not appended) on compaction. */
    private String summaryFact;

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

    /**
     * Compacts the rolling dialogue when it grows past {@link #COMPACT_AFTER}: the oldest lines
     * are folded into a single durable "earlier conversation" fact so long sessions keep a
     * bounded prompt without losing the thread of what was said.
     *
     * @return whether anything was folded
     */
    public synchronized boolean compact() {
        if (recentDialogue.size() <= COMPACT_AFTER) {
            return false;
        }
        StringBuilder summary = new StringBuilder("Earlier conversation: ");
        int fold = recentDialogue.size() - COMPACT_KEEP;
        for (int i = 0; i < fold; i++) {
            if (i > 0) {
                summary.append(" | ");
            }
            summary.append(recentDialogue.removeFirst());
        }
        String text = truncateCodePoints(summary.toString(), MAX_SUMMARY_LENGTH);
        if (summaryFact != null) {
            facts.remove(summaryFact);
        }
        facts.add(text);
        while (facts.size() > MAX_FACTS) {
            var iterator = facts.iterator();
            iterator.next();
            iterator.remove();
        }
        summaryFact = text;
        return true;
    }

    /** Truncates on a code-point boundary so a surrogate pair is never cut in half. */
    private static String truncateCodePoints(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
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
        summaryFact = null;
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
