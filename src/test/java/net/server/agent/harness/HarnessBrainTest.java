package net.server.agent.harness;

import net.server.agent.AgentEvent;
import net.server.agent.AgentObservation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessBrainTest {

    @Test
    void buffersAndDrainsEvents() {
        HarnessBrain brain = new HarnessBrain();
        brain.recordEvents(List.of(new AgentEvent.Instruction("test message")));
        assertEquals(1, brain.bufferedEventCount());
        List<AgentEvent> drained = brain.drainEvents();
        assertEquals(1, drained.size());
        assertEquals(0, brain.bufferedEventCount());
        assertTrue(brain.drainEvents().isEmpty());
    }

    @Test
    void bufferIsBounded() {
        HarnessBrain brain = new HarnessBrain();
        for (int i = 0; i < 500; i++) {
            brain.recordEvents(List.of(new AgentEvent.Instruction("m" + i)));
        }
        assertEquals(128, brain.bufferedEventCount());
    }

    @Test
    void decideIsAlwaysNoOp() {
        HarnessBrain brain = new HarnessBrain();
        assertTrue(brain.decide(new AgentObservation(
                "t", 1, 0, "Beginner", 0, "", "", 50, 50, 5, 5, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()), List.of(), new net.server.agent.AgentMemory(1)).isEmpty());
        assertEquals("test-harness", brain.describe());
    }
}
