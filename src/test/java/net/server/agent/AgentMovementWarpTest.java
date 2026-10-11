package net.server.agent;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import scripting.event.EventInstanceManager;
import server.CashShop;
import server.Trade;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the guard logic for cross-map follow warps. The agent may only warp to a follow
 * target on another map when every transfer-safety condition holds.
 */
class AgentMovementWarpTest {

    private static final int AGENT_MAP = 100_000_000;
    private static final int TARGET_MAP = 100_000_001;

    /** A character that passes every guard; tests then break exactly one condition. */
    private static Character character(int mapId, Client client) {
        CashShop closedCashShop = cashShop(false);
        Character chr = Mockito.mock(Character.class);
        when(chr.getClient()).thenReturn(client);
        when(chr.isAlive()).thenReturn(true);
        when(chr.isChangingMaps()).thenReturn(false);
        when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getEventInstance()).thenReturn(null);
        when(chr.getTrade()).thenReturn(null);
        when(chr.getCashShop()).thenReturn(closedCashShop);
        when(chr.isLoggedinWorld()).thenReturn(true);
        return chr;
    }

    private static Client client(int channel, boolean withChannelServer) {
        Channel channelServer = withChannelServer ? Mockito.mock(Channel.class) : null;
        Client client = Mockito.mock(Client.class);
        when(client.getChannel()).thenReturn(channel);
        when(client.getChannelServer()).thenReturn(channelServer);
        return client;
    }

    private static CashShop cashShop(boolean opened) {
        CashShop cashShop = Mockito.mock(CashShop.class);
        when(cashShop.isOpened()).thenReturn(opened);
        return cashShop;
    }

    @Test
    void warpAllowedWhenEverythingIsSafe() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(TARGET_MAP, client(1, true));

        assertTrue(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void sameMapDoesNotWarp() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(AGENT_MAP, client(1, true));

        assertFalse(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void crossChannelDoesNotWarp() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(TARGET_MAP, client(2, true));

        assertFalse(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void eventInstanceBlocksWarp() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(TARGET_MAP, client(1, true));
        when(target.getEventInstance()).thenReturn(Mockito.mock(EventInstanceManager.class));

        assertFalse(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void deadOrChangingAgentDoesNotWarp() {
        Character dead = character(AGENT_MAP, client(1, true));
        when(dead.isAlive()).thenReturn(false);
        Character changing = character(AGENT_MAP, client(1, true));
        when(changing.isChangingMaps()).thenReturn(true);
        Character target = character(TARGET_MAP, client(1, true));

        assertFalse(AgentMovement.canWarpTo(dead, target));
        assertFalse(AgentMovement.canWarpTo(changing, target));
    }

    @Test
    void tradeOrOpenCashShopBlocksWarp() {
        Character trading = character(AGENT_MAP, client(1, true));
        when(trading.getTrade()).thenReturn(Mockito.mock(Trade.class));
        Character shopping = character(AGENT_MAP, client(1, true));
        CashShop openCashShop = cashShop(true);
        when(shopping.getCashShop()).thenReturn(openCashShop);
        Character target = character(TARGET_MAP, client(1, true));

        assertFalse(AgentMovement.canWarpTo(trading, target));
        assertFalse(AgentMovement.canWarpTo(shopping, target));
    }

    @Test
    void awayTargetBlocksWarp() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(TARGET_MAP, client(1, true));
        when(target.isLoggedinWorld()).thenReturn(false);

        assertFalse(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void missingChannelServerBlocksWarp() {
        Character agent = character(AGENT_MAP, client(1, false));
        Character target = character(TARGET_MAP, client(1, true));

        assertFalse(AgentMovement.canWarpTo(agent, target));
    }

    @Test
    void warpToChangesMapAndClearsTransitionFlag() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(TARGET_MAP, client(1, true));

        assertTrue(AgentMovement.warpTo(agent, target));

        verify(agent).changeMap(TARGET_MAP);
        // Agents never send PLAYER_MAP_TRANSFER, so the warp must clear the flag itself.
        verify(agent).setMapTransitionComplete();
    }

    @Test
    void warpToDoesNothingWhenUnsafe() {
        Character agent = character(AGENT_MAP, client(1, true));
        Character target = character(AGENT_MAP, client(1, true)); // same map

        assertFalse(AgentMovement.warpTo(agent, target));

        verify(agent, never()).changeMap(anyInt());
        verify(agent, never()).setMapTransitionComplete();
    }

    @Test
    void nullInputsAreRejected() {
        Character agent = character(AGENT_MAP, client(1, true));
        assertFalse(AgentMovement.canWarpTo(agent, null));
        assertFalse(AgentMovement.canWarpTo(null, character(TARGET_MAP, client(1, true))));
    }
}
