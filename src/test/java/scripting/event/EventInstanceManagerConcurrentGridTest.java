package scripting.event;

import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the KPQ stage-1 quiz corruption: three concurrent NPC conversations on a
 * shared {@link EventInstanceManager} used to race on the plain-HashMap {@code playerGrid}.
 * The grid (and the other script-facing maps) must now be backed by thread-safe structures so
 * concurrent {@code gridInsert}/{@code gridCheck} never lose or corrupt entries.
 */
class EventInstanceManagerConcurrentGridTest {

    private static final int THREADS = 8;
    private static final int IDS_PER_THREAD = 250;

    private EventInstanceManager newEim() {
        EventManager em = mock(EventManager.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(em.getWorldServer()).thenReturn(world);
        when(em.getChannelServer()).thenReturn(channel);
        when(world.getId()).thenReturn(0);
        when(channel.getId()).thenReturn(1);
        return new EventInstanceManager(em, "concurrency-test");
    }

    private Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private Map<Integer, Integer> playerGrid(EventInstanceManager eim) throws Exception {
        Field field = EventInstanceManager.class.getDeclaredField("playerGrid");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> grid = (Map<Integer, Integer>) field.get(eim);
        return grid;
    }

    @Test
    void playerGridIsBackedByAThreadSafeMap() throws Exception {
        assertInstanceOf(ConcurrentMap.class, playerGrid(newEim()),
                "playerGrid must be thread-safe: it is mutated from concurrent NPC conversations");
    }

    @Test
    void concurrentGridInsertsDoNotLoseOrCorruptEntries() throws Exception {
        EventInstanceManager eim = newEim();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int base = t * IDS_PER_THREAD;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < IDS_PER_THREAD; i++) {
                    int id = base + i;
                    eim.gridInsert(character(id), id);
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(THREADS * IDS_PER_THREAD, eim.gridSize(), "no entries may be lost");
        for (int id = 0; id < THREADS * IDS_PER_THREAD; id++) {
            assertEquals(id, eim.gridCheck(character(id)), "no entry may be corrupted");
        }
    }

    @Test
    void concurrentInsertRemoveAndCheckStayConsistent() throws Exception {
        EventInstanceManager eim = newEim();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int base = t * IDS_PER_THREAD;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < IDS_PER_THREAD; i++) {
                    int id = base + i;
                    Character chr = character(id);
                    eim.gridInsert(chr, id);
                    assertEquals(id, eim.gridCheck(chr));
                    eim.gridRemove(chr);
                    assertEquals(-1, eim.gridCheck(chr));
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, eim.gridSize(), "every inserted entry must be removable under contention");
    }
}
