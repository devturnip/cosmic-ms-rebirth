package net.server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedAgentBrainLootTest {

    private static final String PERSONA = "Alice";

    private final ScriptedAgentBrain brain = new ScriptedAgentBrain(PERSONA);
    private final AgentMemory memory = new AgentMemory(1);

    private static AgentObservation observation(List<AgentObservation.ObservedItem> nearbyItems) {
        return new AgentObservation(
                PERSONA, 1, 0, "Beginner", 100000000, "Henesys", "Henesys",
                100, 100, 100, 100, 0, 0,
                List.of(), List.of(), List.of(), nearbyItems, List.of());
    }

    @Test
    void nearbyItemProducesPickupItemForItsObjectId() {
        AgentObservation.ObservedItem item = new AgentObservation.ObservedItem(98765, 2000000, 1, 0, 10, 20);

        List<AgentAction> actions = brain.decide(observation(List.of(item)), List.of(), memory);

        assertEquals(1, actions.size());
        AgentAction.PickupItem pickup = assertInstanceOf(AgentAction.PickupItem.class, actions.get(0));
        assertEquals(98765, pickup.objectId());
    }

    @Test
    void noNearbyItemsProducesNoPickupItem() {
        List<AgentAction> actions = brain.decide(observation(List.of()), List.of(), memory);

        assertTrue(actions.stream().noneMatch(AgentAction.PickupItem.class::isInstance));
    }
}
