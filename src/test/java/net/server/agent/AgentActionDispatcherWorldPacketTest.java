package net.server.agent;

import net.packet.InPacket;
import net.opcodes.RecvOpcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import server.maps.MapleMap;
import server.maps.Reactor;
import server.life.Monster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packet-shape tests for the world-interaction actions: each must synthesize the exact
 * inbound bytes the real handlers parse ({@code ReactorHitHandler},
 * {@code UseCatchItemHandler}, {@code ItemMoveHandler}).
 */
class AgentActionDispatcherWorldPacketTest {
    private final AgentActionDispatcher dispatcher = new AgentActionDispatcher();
    private AgentSession session;

    @BeforeEach
    void setUp() {
        session = Mockito.mock(AgentSession.class);
        Mockito.when(session.allowAction()).thenReturn(true);
    }

    private InPacket dispatchedPacket(RecvOpcode opcode) {
        ArgumentCaptor<InPacket> captor = ArgumentCaptor.forClass(InPacket.class);
        Mockito.verify(session).dispatchInbound(Mockito.eq(opcode), captor.capture());
        return captor.getValue();
    }

    private void stubPlayerOnMap() {
        var chr = Mockito.mock(client.Character.class);
        Mockito.when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        Mockito.when(session.getPlayer()).thenReturn(chr);
    }

    @Test
    void reactorHitEncodesTheDamageReactorPacket() {
        stubPlayerOnMap();
        Mockito.when(session.getPlayer().getMap().getReactorByOid(7))
                .thenReturn(Mockito.mock(Reactor.class));

        assertTrue(dispatcher.dispatch(session, new AgentAction.ReactorHit(7)));

        InPacket p = dispatchedPacket(RecvOpcode.DAMAGE_REACTOR);
        assertEquals(7, p.readInt(), "reactor oid");
        assertEquals(0, p.readInt(), "charPos");
        assertEquals(1, p.readShort(), "stance (0/2 cannot break type-2 reactors)");
        p.skip(4);
        assertEquals(0, p.readInt(), "skill id (plain hit)");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void reactorHitWithoutTheReactorOnTheMapIsNotDispatched() {
        stubPlayerOnMap(); // getReactorByOid defaults to null

        assertTrue(dispatcher.dispatch(session, new AgentAction.ReactorHit(1234)));
        Mockito.verify(session, Mockito.never()).dispatchInbound(Mockito.any(), Mockito.any());
    }

    @Test
    void catchItemEncodesTheUseCatchItemPacket() {
        stubPlayerOnMap();
        Mockito.when(session.getPlayer().getMap().getMonsterByOid(42))
                .thenReturn(Mockito.mock(Monster.class));

        assertTrue(dispatcher.dispatch(session, new AgentAction.CatchItem(42, 2270004)));

        InPacket p = dispatchedPacket(RecvOpcode.USE_CATCH_ITEM);
        p.skip(4); // timestamp block the handler skips
        p.skip(2); // short block the handler skips
        assertEquals(2270004, p.readInt(), "catch item id");
        assertEquals(42, p.readInt(), "mob oid");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void catchItemWithoutTheMobOnTheMapIsNotDispatched() {
        stubPlayerOnMap(); // getMonsterByOid defaults to null

        assertTrue(dispatcher.dispatch(session, new AgentAction.CatchItem(1, 2270004)));
        Mockito.verify(session, Mockito.never()).dispatchInbound(Mockito.any(), Mockito.any());
    }

    @Test
    void dropItemEncodesTheItemMoveDropPacket() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.DropItem("ETC", (short) 9, (short) 4)));

        InPacket p = dispatchedPacket(RecvOpcode.ITEM_MOVE);
        p.skip(4); // timestamp block the handler skips
        assertEquals(4, p.readByte(), "ETC inventory type");
        assertEquals(9, p.readShort(), "src slot");
        assertEquals(0, p.readShort(), "action 0 = drop");
        assertEquals(4, p.readShort(), "quantity");
        assertEquals(0, p.available(), "packet fully consumed");
    }

    @Test
    void dropItemWithUnknownInventoryTypeIsNotDispatched() {
        assertTrue(dispatcher.dispatch(session, new AgentAction.DropItem("NOPE", (short) 1, (short) 1)));
        Mockito.verify(session, Mockito.never()).dispatchInbound(Mockito.any(), Mockito.any());
    }
}
