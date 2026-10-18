package net.server.agent.harness;

import net.server.agent.AgentAction;
import net.server.agent.AgentBrain;
import net.server.agent.AgentEvent;
import net.server.agent.AgentObservation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The brain of a test-harness session. Unlike {@code LlmAgentBrain} it never calls an LLM and
 * never makes its own decisions: the testing agent drives the character from the outside (over
 * MCP). Its {@link #decide} is therefore never expected to run — {@code AgentSession.tick}
 * short-circuits harness sessions — but if it ever does, returning empty is the safe no-op.
 * <p>
 * It buffers the {@link AgentEvent}s drained from the session's inbox so the external agent can
 * read them via {@code observe} (e.g. map chat, map changes, party invites).
 */
public final class HarnessBrain implements AgentBrain {
    /** Bounded ring of recent events; older events are dropped as new ones arrive. */
    private static final int MAX_BUFFERED_EVENTS = 128;

    private final Deque<AgentEvent> recentEvents = new ArrayDeque<>();

    @Override
    public String describe() {
        return "test-harness";
    }

    @Override
    public List<AgentAction> decide(AgentObservation observation, List<AgentEvent> newEvents,
                                    net.server.agent.AgentMemory memory) {
        recordEvents(newEvents);
        return List.of(); // externally driven; the harness never decides on its own
    }

    /** Appends drained session events to the bounded buffer (called by the tick thread). */
    public synchronized void recordEvents(List<AgentEvent> events) {
        if (events == null) {
            return;
        }
        for (AgentEvent event : events) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_BUFFERED_EVENTS) {
                recentEvents.pollFirst();
            }
        }
    }

    /** Returns and clears the buffered events (called by the harness service). */
    public synchronized List<AgentEvent> drainEvents() {
        List<AgentEvent> drained = new ArrayList<>(recentEvents);
        recentEvents.clear();
        return drained;
    }

    /** Snapshot of buffered events without clearing (for debugging). */
    public synchronized int bufferedEventCount() {
        return recentEvents.size();
    }
}
