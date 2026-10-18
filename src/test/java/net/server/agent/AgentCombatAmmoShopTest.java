package net.server.agent;

import client.Character;
import client.BuffStat;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.life.NPC;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the ammo-resupply path behind the {@link AgentAction.RechargeAmmo} action
 * ({@code AgentCombat.rechargeAmmo} / {@code AgentCombat.buyAmmoFromShop}): rechargeable
 * stars/bullets go through the real {@link Shop#recharge}, arrows (not rechargeable in v83)
 * go through the real {@link Shop#buy}, mesos are respected exactly like a player purchase,
 * and missing stock / unaffordable lines / shop failures are handled.
 *
 * <p>{@code ItemInformationProvider}'s singleton touches the database during class
 * initialization ({@code loadCardIdData}); since unit tests have no database, a throwing
 * mock connection pool is installed for the duration of the class so the singleton can
 * initialize (the SQL failure is swallowed there), and the {@code slotMaxCache} is seeded
 * with the values the assertions rely on.</p>
 */
class AgentCombatAmmoShopTest {

    private static final int RECHARGE_NPC_ID = 9100100;
    private static final int ARROW_FOR_BOW = 2060000;
    private static final int THROWING_STAR = 2070004;

    private static boolean fakePoolInstalled;
    private static Field dataSourceField;

    private Character chr;
    private Client client;
    private Shop shop;
    private Inventory use;

    @BeforeAll
    static void allowItemInformationProviderInitWithoutDb() throws Exception {
        dataSourceField = DatabaseConnection.class.getDeclaredField("dataSource");
        dataSourceField.setAccessible(true);
        if (dataSourceField.get(null) == null) {
            HikariDataSource fakePool = mock(HikariDataSource.class);
            when(fakePool.getConnection()).thenThrow(new SQLException("unit tests have no database"));
            dataSourceField.set(null, fakePool);
            fakePoolInstalled = true;
        }
        // Force the singleton to initialize now (while the fake pool is in place).
        ItemInformationProvider.getInstance();
    }

    @AfterAll
    static void restoreConnectionPool() throws Exception {
        if (fakePoolInstalled) {
            dataSourceField.set(null, null);
        }
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void cleanupSingletons() throws Exception {
        if (RECHARGE_NPC_ID != 0) {
            Field npcShops = ShopFactory.class.getDeclaredField("npcShops");
            npcShops.setAccessible(true);
            ((Map<Integer, Shop>) npcShops.get(ShopFactory.getInstance())).remove(RECHARGE_NPC_ID);
        }
        Field slotMaxCache = ItemInformationProvider.class.getDeclaredField("slotMaxCache");
        slotMaxCache.setAccessible(true);
        ((Map<Integer, Short>) slotMaxCache.get(ItemInformationProvider.getInstance())).remove(ARROW_FOR_BOW);
    }

    @SuppressWarnings("unchecked")
    private static void seedSlotMax(int itemId, short slotMax) throws Exception {
        Field slotMaxCache = ItemInformationProvider.class.getDeclaredField("slotMaxCache");
        slotMaxCache.setAccessible(true);
        ((Map<Integer, Short>) slotMaxCache.get(ItemInformationProvider.getInstance())).put(itemId, slotMax);
    }

    @SuppressWarnings("unchecked")
    private static void putShopForNpc(int npcId, Shop shop) throws Exception {
        Field npcShops = ShopFactory.class.getDeclaredField("npcShops");
        npcShops.setAccessible(true);
        ((Map<Integer, Shop>) npcShops.get(ShopFactory.getInstance())).put(npcId, shop);
    }

    /** A living ranged agent with a shop NPC in recharge range and the given USE item (may be null). */
    private void setUpRangedAgent(int weaponItemId, Item useItem) throws Exception {
        chr = mock(Character.class);
        client = mock(Client.class);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getLevel()).thenReturn(120);
        when(chr.getClient()).thenReturn(client);
        when(chr.getMeso()).thenReturn(50_000);

        when(chr.getBuffedValue(BuffStat.SOULARROW)).thenReturn(null);
        when(chr.getBuffedValue(BuffStat.SHADOW_CLAW)).thenReturn(null);

        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(0, 0));

        Inventory equipped = mock(Inventory.class);
        Item weapon = mock(Item.class);
        when(weapon.getItemId()).thenReturn(weaponItemId);
        when(equipped.getItem((short) -11)).thenReturn(weapon);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);

        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(RECHARGE_NPC_ID);
        when(map.getMapObjectsInRange(any(), anyDouble(), anyList())).thenReturn(List.of(npc));

        use = mock(Inventory.class);
        when(use.listCopy()).thenReturn(useItem == null ? List.of() : List.of(useItem));
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);

        shop = mock(Shop.class);
        putShopForNpc(RECHARGE_NPC_ID, shop);
    }

    private static Item ammoItem(int itemId, int... quantities) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        Short[] qtys = Arrays.stream(quantities).mapToObj(q -> (short) q).toArray(Short[]::new);
        when(item.getQuantity()).thenReturn(qtys[0], Arrays.copyOfRange(qtys, 1, qtys.length, Short[].class));
        when(item.getPosition()).thenReturn((short) 2);
        return item;
    }

    // ------------------------------------------------------------------
    // stars/bullets: the real Shop#recharge path
    // ------------------------------------------------------------------

    @Test
    void rechargeAmmoRefillsStarsThroughShopRecharge() throws Exception {
        setUpRangedAgent(1472000, ammoItem(THROWING_STAR, 5, 5, 9)); // claw + throwing star

        assertTrue(AgentCombat.rechargeAmmo(session()),
                "a refilled stack must report success");

        verify(shop).recharge(client, (short) 2);
        verify(shop, never()).buy(any(), anyShort(), anyInt(), anyShort());
    }

    @Test
    void rechargeAmmoNeverBuysRechargeableAmmoDirectly() throws Exception {
        setUpRangedAgent(1472000, ammoItem(THROWING_STAR, 5, 5, 9));
        // A star is also listed in the shop: it must still be recharged, never bought.
        when(shop.getItems()).thenReturn(List.of(new ShopItem((short) 1, THROWING_STAR, 1, 0)));

        AgentCombat.rechargeAmmo(session());

        verify(shop).recharge(client, (short) 2);
        verify(shop, never()).buy(any(), anyShort(), anyInt(), anyShort());
    }

    // ------------------------------------------------------------------
    // arrows: the real Shop#buy path
    // ------------------------------------------------------------------

    @Test
    void rechargeAmmoBuysArrowsThroughShopBuy() throws Exception {
        seedSlotMax(ARROW_FOR_BOW, (short) 1000);
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0)); // bow + empty arrow stack
        when(shop.getItems()).thenReturn(List.of(new ShopItem((short) 1, ARROW_FOR_BOW, 1, 0)));
        when(use.countById(ARROW_FOR_BOW)).thenReturn(0, 1);

        assertTrue(AgentCombat.rechargeAmmo(session()),
                "a successful arrow purchase must report success");

        verify(shop, never()).recharge(any(), anyShort());
        verify(shop).buy(client, (short) 0, ARROW_FOR_BOW, (short) 1000);
    }

    @Test
    void arrowPurchaseOnlySpendsAffordableMesos() throws Exception {
        seedSlotMax(ARROW_FOR_BOW, (short) 1000);
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0));
        when(chr.getMeso()).thenReturn(1234);
        when(shop.getItems()).thenReturn(List.of(new ShopItem((short) 1, ARROW_FOR_BOW, 100, 0)));
        when(use.countById(ARROW_FOR_BOW)).thenReturn(0, 12);

        assertTrue(AgentCombat.rechargeAmmo(session()));

        // 1234 mesos at price 100 buys exactly 12 arrows - the real Shop#buy does the paying.
        verify(shop).buy(client, (short) 0, ARROW_FOR_BOW, (short) 12);
        verify(chr, never()).gainMeso(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(chr, never()).gainMeso(anyInt());
    }

    @Test
    void arrowPurchaseScansPastAnUnaffordableStockLine() throws Exception {
        seedSlotMax(ARROW_FOR_BOW, (short) 1000);
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0));
        when(chr.getMeso()).thenReturn(500);
        when(shop.getItems()).thenReturn(List.of(
                new ShopItem((short) 1, ARROW_FOR_BOW, 1000, 0),  // unaffordable line
                new ShopItem((short) 1, ARROW_FOR_BOW, 1, 0)));   // affordable line, slot 1
        when(use.countById(ARROW_FOR_BOW)).thenReturn(0, 500);

        assertTrue(AgentCombat.rechargeAmmo(session()));

        verify(shop, never()).buy(client, (short) 0, ARROW_FOR_BOW, (short) 500);
        verify(shop).buy(client, (short) 1, ARROW_FOR_BOW, (short) 500);
    }

    // ------------------------------------------------------------------
    // shop cannot serve the agent
    // ------------------------------------------------------------------

    @Test
    void rechargeAmmoReturnsFalseWhenShopHasNoStock() throws Exception {
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0));
        when(shop.getItems()).thenReturn(List.of());

        assertFalse(AgentCombat.rechargeAmmo(session()));
        verify(shop, never()).buy(any(), anyShort(), anyInt(), anyShort());
        verify(shop, never()).recharge(any(), anyShort());
    }

    @Test
    void rechargeAmmoIgnoresStockTheWeaponCannotFire() throws Exception {
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0));
        // A bow agent cannot use throwing stars (and they are rechargeable anyway).
        when(shop.getItems()).thenReturn(List.of(new ShopItem((short) 1, THROWING_STAR, 1, 0)));

        assertFalse(AgentCombat.rechargeAmmo(session()));
        verify(shop, never()).buy(any(), anyShort(), anyInt(), anyShort());
    }

    @Test
    void rechargeAmmoReturnsFalseWhenShopBuyThrows() throws Exception {
        seedSlotMax(ARROW_FOR_BOW, (short) 1000);
        setUpRangedAgent(1452000, ammoItem(ARROW_FOR_BOW, 0));
        when(shop.getItems()).thenReturn(List.of(new ShopItem((short) 1, ARROW_FOR_BOW, 1, 0)));
        when(use.countById(ARROW_FOR_BOW)).thenReturn(0, 0);
        doThrow(new RuntimeException("shop is busy")).when(shop).buy(any(), anyShort(), anyInt(), anyShort());

        assertFalse(AgentCombat.rechargeAmmo(session()), "a failing purchase must not report success");
    }

    private AgentSession session() {
        AgentSession session = mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        return session;
    }
}
