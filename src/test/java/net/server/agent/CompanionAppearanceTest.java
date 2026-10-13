package net.server.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Pure-selection tests for {@link CompanionAppearance}: a seeded {@link Random} must pick from the
 * supplied candidate lists, never wear two items in the same cash slot, and fall back to the
 * defaults when the WZ data is empty.
 */
class CompanionAppearanceTest {

    private static final List<Integer> HAIRS = List.of(30000, 30010, 30020);
    private static final List<Integer> FACES = List.of(20000, 20001, 20002);

    @Test
    void seededRandomPicksValidHairFaceSkinAndCash() {
        final List<CompanionAppearance.CashEquip> cash = List.of(
                new CompanionAppearance.CashEquip(1000000, (byte) -101),
                new CompanionAppearance.CashEquip(1050000, (byte) -105),
                new CompanionAppearance.CashEquip(1070000, (byte) -107));

        final CompanionAppearance appearance = new CompanionAppearance(HAIRS, FACES, cash, new Random(42));
        final CompanionAppearance.Profile profile = appearance.roll();

        assertTrue(HAIRS.contains(profile.hair()), "hair must come from the WZ candidates");
        assertTrue(FACES.contains(profile.face()), "face must come from the WZ candidates");
        assertNotNull(profile.skin(), "a skin colour must always be chosen");
        assertFalse(profile.cash().isEmpty(), "cash candidates should produce a cash outfit");
        for (CompanionAppearance.CashEquip equipped : profile.cash()) {
            assertTrue(equipped.position() <= -101, "cash position must be a worn cash slot");
            assertTrue(cash.contains(equipped), "cash item must come from the candidate list");
        }
    }

    @Test
    void neverWearsTwoItemsInTheSameCashSlot() {
        final List<CompanionAppearance.CashEquip> cash = List.of(
                new CompanionAppearance.CashEquip(1000000, (byte) -101),
                new CompanionAppearance.CashEquip(1000001, (byte) -101),
                new CompanionAppearance.CashEquip(1050000, (byte) -105),
                new CompanionAppearance.CashEquip(1050001, (byte) -105),
                new CompanionAppearance.CashEquip(1070000, (byte) -107));

        for (int seed = 0; seed < 100; seed++) {
            final CompanionAppearance.Profile profile =
                    new CompanionAppearance(HAIRS, FACES, cash, new Random(seed)).roll();
            final long distinctPositions = profile.cash().stream()
                    .map(CompanionAppearance.CashEquip::position)
                    .distinct()
                    .count();
            assertEquals(profile.cash().size(), distinctPositions,
                    "each cash position must appear at most once");
        }
    }

    @Test
    void emptyCandidatesFallBackToDefaults() {
        final CompanionAppearance appearance =
                new CompanionAppearance(List.of(), List.of(), List.of(), new Random(7));
        final CompanionAppearance.Profile profile = appearance.roll();

        assertEquals(CompanionAppearance.DEFAULT_HAIR, profile.hair());
        assertEquals(CompanionAppearance.DEFAULT_FACE, profile.face());
        assertNotNull(profile.skin(), "skin falls back to a valid colour, never null");
        assertTrue(profile.cash().isEmpty(), "no candidates means no cash outfit");
    }

    @Test
    void cashPositionMapsKnownSlots() {
        assertEquals((byte) -101, (byte) CompanionAppearance.cashPosition("Cp"));
        assertEquals((byte) -102, (byte) CompanionAppearance.cashPosition("Af"));
        assertEquals((byte) -103, (byte) CompanionAppearance.cashPosition("Ay"));
        assertEquals((byte) -104, (byte) CompanionAppearance.cashPosition("Ae"));
        assertEquals((byte) -105, (byte) CompanionAppearance.cashPosition("Ma"));
        assertEquals((byte) -105, (byte) CompanionAppearance.cashPosition("MaPn"));
        assertEquals((byte) -106, (byte) CompanionAppearance.cashPosition("Pn"));
        assertEquals((byte) -107, (byte) CompanionAppearance.cashPosition("So"));
        assertEquals((byte) -108, (byte) CompanionAppearance.cashPosition("GlGw"));
        assertEquals((byte) -108, (byte) CompanionAppearance.cashPosition("Gv"));
        assertEquals((byte) -109, (byte) CompanionAppearance.cashPosition("Sr"));
        assertEquals((byte) -110, (byte) CompanionAppearance.cashPosition("Si"));
        assertEquals((byte) -111, (byte) CompanionAppearance.cashPosition("Wp"));
        assertEquals((byte) -111, (byte) CompanionAppearance.cashPosition("WpSp"));
    }

    @Test
    void unsupportedSlotsHaveNoCashPosition() {
        assertNull(CompanionAppearance.cashPosition(null));
        assertNull(CompanionAppearance.cashPosition(""));
        assertNull(CompanionAppearance.cashPosition("Ri")); // ring, outside the outfit range
        assertNull(CompanionAppearance.cashPosition("Pe")); // pendant, outside the outfit range
        assertNull(CompanionAppearance.cashPosition("nonsense"));
    }
}
