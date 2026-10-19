package scripting.npc;

import client.Client;
import org.junit.jupiter.api.Test;
import scripting.quest.QuestScriptManager;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the script-manager conversation maps: {@code cms} and {@code scripts} are
 * consulted and mutated from many channel threads at once (one per talking player), so they must
 * be thread-safe. A full {@code start()}/{@code dispose()} round-trip needs a live client plus a
 * script engine, so the singleton's maps are exercised reflectively here instead.
 */
class ScriptManagerMapsConcurrencyTest {

    private static Map<?, ?> field(Object holder, String name) throws Exception {
        Field field = holder.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(holder);
    }

    private static void assertThreadSafe(Map<?, ?> map, String name) {
        assertInstanceOf(ConcurrentMap.class, map, name + " must be thread-safe");
    }

    @Test
    void npcScriptManagerMapsAreThreadSafe() throws Exception {
        NPCScriptManager npc = NPCScriptManager.getInstance();
        assertThreadSafe(field(npc, "cms"), "NPCScriptManager.cms");
        assertThreadSafe(field(npc, "scripts"), "NPCScriptManager.scripts");
    }

    @Test
    void questScriptManagerMapsAreThreadSafe() throws Exception {
        QuestScriptManager quest = QuestScriptManager.getInstance();
        assertThreadSafe(field(quest, "qms"), "QuestScriptManager.qms");
        assertThreadSafe(field(quest, "scripts"), "QuestScriptManager.scripts");
    }

    @Test
    void concurrentConversationsDoNotCorruptTheCmsMap() throws Exception {
        // Hammer the real singleton's cms map with distinct mock clients, like simultaneous NPC
        // talks; unique keys keep the singleton's state untouched for other tests.
        Map<Client, Object> cms = (Map<Client, Object>) field(NPCScriptManager.getInstance(), "cms");
        assertInstanceOf(ConcurrentHashMap.class, cms);

        int threads = 8, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    Client c = mock(Client.class);
                    int key = System.identityHashCode(c) + i; // unique per iteration
                    cms.put(c, key);
                    assertEquals(key, cms.get(c));
                    assertEquals(key, cms.remove(c));
                    assertNull(cms.get(c));
                }
                return null;
            }));
        }

        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
