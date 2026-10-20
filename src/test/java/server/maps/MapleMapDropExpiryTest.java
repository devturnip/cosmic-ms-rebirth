package server.maps;

import client.Character;
import client.inventory.Item;
import net.server.Server;
import org.junit.jupiter.api.Test;
import server.TimerManager;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the drop-expiry reaper:
 * <ul>
 *   <li>an expired drop present on the map is removed from the map objects and the expiry
 *       table by {@code makeDisappearExpiredItemDrops} (this is what used to leave expired
 *       LPQ passes lingering unpickable);</li>
 *   <li>{@code instantiateItemDrop} guarantees the item monitor — and with it the expiry
 *       reaper — is registered on every map kind, not only via addPlayer's first-player
 *       gate.</li>
 * </ul>
 * Uses reflection on package-private state; no DB or live server required.
 */
class MapleMapDropExpiryTest {

    private static final int MAP_ID = 922010200; // LPQ stage 2, an event-instance map

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void invokePrivate(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            method.invoke(target);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void invokePrivate(Object target, String name, Object arg) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, MapItem.class);
        method.setAccessible(true);
        try {
            method.invoke(target, arg);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static MapItem newDrop(MapleMap map, int oid) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);

        Item item = new Item(4001022, (short) 0, (short) 1); // LPQ pass
        MapItem mdrop = new MapItem(item, new Point(0, 0), null, owner, owner.getClient(), (byte) 0, false, 0);
        mdrop.setObjectId(oid);
        return mdrop;
    }

    @SuppressWarnings("unchecked")
    private static void putOnMap(MapleMap map, MapItem mdrop, long expiryDeadline) throws Exception {
        Map<Integer, MapObject> mapobjects = (Map<Integer, MapObject>) getField(map, "mapobjects");
        mapobjects.put(mdrop.getObjectId(), mdrop);

        Map<MapItem, Long> droppedItems = (Map<MapItem, Long>) getField(map, "droppedItems");
        droppedItems.put(mdrop, expiryDeadline);
    }

    @Test
    void expiredDropIsRemovedByReaper() throws Exception {
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 100000000, 0);
        long now = Server.getInstance().forceUpdateCurrentTime();

        MapItem expired = newDrop(map, 123456);
        MapItem fresh = newDrop(map, 123457);

        // expired: deadline in the past; fresh: deadline far in the future
        putOnMap(map, expired, now - 1000);
        putOnMap(map, fresh, now + 10 * 60 * 1000L);

        invokePrivate(map, "makeDisappearExpiredItemDrops");

        // expired drop is gone from the map objects...
        assertNull(map.getMapObject(123456));
        assertTrue(expired.isPickedUp());
        // ...and from the expiry table; the fresh drop is untouched.
        Map<?, ?> droppedItems = (Map<?, ?>) getField(map, "droppedItems");
        assertFalse(droppedItems.containsKey(expired));
        assertTrue(droppedItems.containsKey(fresh));
        assertEquals(1, droppedItems.size());
    }

    @Test
    void instantiateItemDropGuaranteesItemMonitorIsRunning() throws Exception {
        TimerManager.getInstance().start();
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 100000000, 0);
        assertFalse((Boolean) invokeGetter(map, "hasItemMonitor"),
                "precondition: monitor not yet started on a fresh map");

        invokePrivate(map, "instantiateItemDrop", newDrop(map, 555001));

        assertTrue((Boolean) invokeGetter(map, "hasItemMonitor"),
                "spawning a drop must guarantee the expiry reaper is scheduled");
        ScheduledFuture<?> expireItemsTask =
                (ScheduledFuture<?>) getField(map, "expireItemsTask");
        assertTrue(expireItemsTask != null && !expireItemsTask.isCancelled());

        // registered drop is present in the expiry table
        Map<?, ?> droppedItems = (Map<?, ?>) getField(map, "droppedItems");
        assertEquals(1, droppedItems.size());
    }

    private static Object invokeGetter(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

}
