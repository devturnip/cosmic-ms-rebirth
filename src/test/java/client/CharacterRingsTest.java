package client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the ring-list snapshot fix: getCrushRings/getFriendshipRings used to
 * hand out the live backing list (and sort it in place), so a concurrent mutation — e.g. a
 * crush ring bought from the cash shop while a ring-look broadcast iterates the list —
 * threw ConcurrentModificationException.
 */
class CharacterRingsTest {

    private static Character newCharacter() throws Exception {
        var ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void getCrushRingsReturnsSnapshotNotLiveList() throws Exception {
        Character chr = newCharacter();
        chr.addCrushRing(new Ring(1001, 1002, 2, 1112300, "partnerA"));

        List<Ring> snapshot = chr.getCrushRings();
        assertEquals(1, snapshot.size());

        // A later mutation must not hit the previously handed-out list (nor throw CME on it).
        chr.addCrushRing(new Ring(1003, 1004, 3, 1112301, "partnerB"));

        assertEquals(1, snapshot.size());
        assertEquals(2, chr.getCrushRings().size());
        assertNotSame(snapshot, chr.getCrushRings());
    }

    @Test
    void getCrushRingsIsSortedByRingId() throws Exception {
        Character chr = newCharacter();
        chr.addCrushRing(new Ring(1005, 1006, 2, 1112300, "late"));
        chr.addCrushRing(new Ring(1001, 1002, 3, 1112301, "early"));

        List<Ring> rings = chr.getCrushRings();
        assertEquals(1001, rings.get(0).getRingId());
        assertEquals(1005, rings.get(1).getRingId());
    }

    @Test
    void getFriendshipRingsReturnsSnapshotNotLiveList() throws Exception {
        Character chr = newCharacter();
        chr.addFriendshipRing(new Ring(2001, 2002, 2, 1112400, "friendA"));

        List<Ring> snapshot = chr.getFriendshipRings();
        assertEquals(1, snapshot.size());

        chr.addFriendshipRing(new Ring(2003, 2004, 3, 1112401, "friendB"));

        assertEquals(1, snapshot.size());
        assertEquals(2, chr.getFriendshipRings().size());
        assertNotSame(snapshot, chr.getFriendshipRings());
    }

    @Test
    void concurrentIterationWhileAddingDoesNotThrow() throws Exception {
        final Character chr = newCharacter();
        chr.addCrushRing(new Ring(1001, 1002, 2, 1112300, "partner"));

        final java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                try {
                    for (Ring ignored : chr.getCrushRings()) {
                        // touch the iterator
                    }
                } catch (Throwable t) {
                    errors.add(t);
                    return;
                }
            }
        });
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                chr.addCrushRing(new Ring(1000 + i, 0, 0, 1112300, "bulk" + i));
            }
        });

        reader.start();
        writer.start();
        reader.join(10_000);
        writer.join(10_000);

        assertTrue(errors.isEmpty(), () -> "iteration threw: " + errors);
    }
}
