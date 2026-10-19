package net.server.agent.harness;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import net.server.agent.AgentAction;
import net.server.agent.AgentSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.maps.ReactorStats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance-verification tests for the world-interaction actions: {@code capture} /
 * {@code verify} must report whether the server state actually changed, using a real
 * {@link Reactor} plus mocked map/inventory state (mirroring how the handlers mutate them).
 */
class HarnessWorldVerifierTest {
    private final AgentSession session = Mockito.mock(AgentSession.class);
    private final MapleMap map = Mockito.mock(MapleMap.class);

    private Character player;

    private Character mockCharacter() {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getMap()).thenReturn(map);
        Mockito.when(session.getPlayer()).thenReturn(chr);
        return chr;
    }

    @Test
    void reactorHitVerifiesWhenTheStateAdvances() {
        player = mockCharacter();
        Reactor reactor = new Reactor(new ReactorStats(), 2202003);
        Mockito.when(map.getReactorByOid(5)).thenReturn(reactor);

        Object pre = HarnessVerifier.capture(session, new AgentAction.ReactorHit(5));
        assertEquals(0L, ((long[]) pre)[1], "initial state captured");

        Mockito.when(map.getReactorByOid(5)).thenReturn(null); // destroyed & removed
        assertTrue(HarnessVerifier.verify(session, new AgentAction.ReactorHit(5), pre),
                "reactor removal counts as accepted");

        // state advance on a live reactor
        reactor.setState((byte) 1);
        Mockito.when(map.getReactorByOid(5)).thenReturn(reactor);
        assertTrue(HarnessVerifier.verify(session, new AgentAction.ReactorHit(5), pre));

        // refused hit: nothing changed since the fresh capture
        Object pre2 = HarnessVerifier.capture(session, new AgentAction.ReactorHit(5));
        assertFalse(HarnessVerifier.verify(session, new AgentAction.ReactorHit(5), pre2));
    }

    @Test
    void reactorHitOnAnUnknownReactorIsRefused() {
        player = mockCharacter(); // no reactor resolvable

        Object pre = HarnessVerifier.capture(session, new AgentAction.ReactorHit(9));
        assertEquals(0L, ((long[]) pre)[0], "absent-reactor sentinel captured");
        assertFalse(HarnessVerifier.verify(session, new AgentAction.ReactorHit(9), pre));
    }

    @Test
    void catchItemVerifiesViaMobKillOrItemConsumption() {
        player = mockCharacter();
        Monster mob = Mockito.mock(Monster.class);
        Mockito.when(map.getMonsterByOid(42)).thenReturn(mob);
        Inventory use = Mockito.mock(Inventory.class);
        Item marble = marbleItem();
        Mockito.when(use.listById(2270004)).thenReturn(java.util.List.of(marble));
        Mockito.when(player.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);

        AgentAction.CatchItem action = new AgentAction.CatchItem(42, 2270004);
        Object pre = HarnessVerifier.capture(session, action);

        // successful catch: the mob is killed
        Mockito.when(map.getMonsterByOid(42)).thenReturn(null);
        assertTrue(HarnessVerifier.verify(session, action, pre));

        // item consumed without the mob dying also signals acceptance
        Mockito.when(map.getMonsterByOid(42)).thenReturn(mob);
        Mockito.when(marble.getQuantity()).thenReturn((short) 9);
        assertTrue(HarnessVerifier.verify(session, action, pre));

        // failed catch (e.g. HP too high): mob and inventory untouched
        Mockito.when(marble.getQuantity()).thenReturn((short) 10);
        assertFalse(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void catchItemWithoutTheMobPresentIsRefused() {
        player = mockCharacter(); // getMonsterByOid defaults to null

        Object pre = HarnessVerifier.capture(session, new AgentAction.CatchItem(1, 2270004));
        assertEquals(0L, ((long[]) pre)[0], "absent-mob sentinel captured");
        assertFalse(HarnessVerifier.verify(session, new AgentAction.CatchItem(1, 2270004), pre));
    }

    @Test
    void dropItemVerifiesViaTheShrinkingStack() {
        player = mockCharacter();
        Inventory etc = Mockito.mock(Inventory.class);
        Item stack = Mockito.mock(Item.class);
        Mockito.when(stack.getQuantity()).thenReturn((short) 10);
        Mockito.when(etc.getItem((short) 9)).thenReturn(stack);
        Mockito.when(player.getInventory(client.inventory.InventoryType.ETC)).thenReturn(etc);

        AgentAction.DropItem action = new AgentAction.DropItem("ETC", (short) 9, (short) 4);
        Object pre = HarnessVerifier.capture(session, action);

        // partial drop: same slot, smaller stack
        Mockito.when(stack.getQuantity()).thenReturn((short) 6);
        assertTrue(HarnessVerifier.verify(session, action, pre));

        // full drop: slot emptied
        Mockito.when(etc.getItem((short) 9)).thenReturn(null);
        assertTrue(HarnessVerifier.verify(session, action, pre));

        // refused drop: stack untouched
        Mockito.when(etc.getItem((short) 9)).thenReturn(stack);
        Mockito.when(stack.getQuantity()).thenReturn((short) 10);
        assertFalse(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void dropItemFromAnEmptySlotIsNotObservable() {
        player = mockCharacter(); // getItem defaults to null

        Object pre = HarnessVerifier.capture(session, new AgentAction.DropItem("ETC", (short) 3, (short) 1));
        assertNull(pre);
        assertNull(HarnessVerifier.verify(session, new AgentAction.DropItem("ETC", (short) 3, (short) 1), pre));
    }

    private static Item marbleItem() {
        Item item = Mockito.mock(Item.class);
        Mockito.when(item.getQuantity()).thenReturn((short) 10);
        return item;
    }
}
