package net.server.agent;

import client.SkinColor;
import constants.inventory.EquipSlot;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random cosmetic profile for an ephemeral player companion: a hair id, a face id, a skin colour
 * and a set of cash equips. The selection is a pure function of the injected {@link Random} plus
 * the candidate lists, so it can be unit-tested without WZ or a database. {@link #roll(Random)}
 * loads (and caches) the candidate lists from the WZ data.
 * <p>
 * Cosmetic problems never abort provisioning: an empty candidate list falls back to face 20000 /
 * hair 30000 / {@link SkinColor#NORMAL} / no cash.
 */
final class CompanionAppearance {

    static final int DEFAULT_FACE = 20000;
    static final int DEFAULT_HAIR = 30000;
    static final int DEFAULT_SKIN = SkinColor.NORMAL.getId();

    /** Worn cash items occupy {@code regular slot - 100}; these bound the supported outfit slots. */
    static final int MIN_CASH_POSITION = -111; // weapon
    static final int MAX_CASH_POSITION = -101; // hat

    /** A cash equip candidate together with the negative position it is worn at. */
    record CashEquip(int itemId, byte position) {
    }

    /** The selected cosmetic profile. */
    record Profile(int hair, int face, SkinColor skin, List<CashEquip> cash) {
    }

    private static volatile List<Integer> hairIdCache;
    private static volatile List<Integer> faceIdCache;
    private static volatile List<CashEquip> cashCandidateCache;
    private static final Object CASH_LOAD_LOCK = new Object();
    private static volatile boolean cashLoadStarted;

    private final List<Integer> hairIds;
    private final List<Integer> faceIds;
    private final List<CashEquip> cashCandidates;
    private final Random random;

    CompanionAppearance(List<Integer> hairIds, List<Integer> faceIds,
                        List<CashEquip> cashCandidates, Random random) {
        this.hairIds = hairIds == null ? List.of() : List.copyOf(hairIds);
        this.faceIds = faceIds == null ? List.of() : List.copyOf(faceIds);
        this.cashCandidates = cashCandidates == null ? List.of() : List.copyOf(cashCandidates);
        this.random = random == null ? ThreadLocalRandom.current() : random;
    }

    /** Builds the profile from the WZ-loaded (cached) candidate lists. */
    static Profile roll(Random random) {
        return new CompanionAppearance(hairIds(), faceIds(), cashCandidates(), random).roll();
    }

    Profile roll() {
        return new Profile(selectHair(), selectFace(), selectSkin(), selectCash());
    }

    int selectHair() {
        return pick(hairIds, DEFAULT_HAIR);
    }

    int selectFace() {
        return pick(faceIds, DEFAULT_FACE);
    }

    SkinColor selectSkin() {
        final SkinColor[] colors = SkinColor.values();
        if (colors.length == 0) {
            return SkinColor.getById(DEFAULT_SKIN);
        }
        return colors[random.nextInt(colors.length)];
    }

    /**
     * Picks at most one cash item per worn position (so a top and an overall, or regular and cash
     * gloves, can never both be worn). Positions outside the supported outfit range are ignored.
     */
    List<CashEquip> selectCash() {
        final Map<Byte, List<CashEquip>> byPosition = new LinkedHashMap<>();
        for (CashEquip candidate : cashCandidates) {
            if (candidate.position() < MIN_CASH_POSITION || candidate.position() > MAX_CASH_POSITION) {
                continue;
            }
            byPosition.computeIfAbsent(candidate.position(), key -> new ArrayList<>()).add(candidate);
        }
        final List<CashEquip> selected = new ArrayList<>(byPosition.size());
        for (List<CashEquip> group : byPosition.values()) {
            selected.add(group.get(random.nextInt(group.size())));
        }
        return selected;
    }

    private int pick(List<Integer> candidates, int fallback) {
        if (candidates.isEmpty()) {
            return fallback;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    // ------------------------------------------------------------------
    // WZ candidate loading (cached)
    // ------------------------------------------------------------------

    /** Valid hair ids enumerated from {@code Character.wz/Hair} (e.g. {@code 00030000.img} -> 30000). */
    static List<Integer> hairIds() {
        List<Integer> cached = hairIdCache;
        if (cached == null) {
            cached = loadEquipIds("Hair");
            hairIdCache = cached;
        }
        return cached;
    }

    /** Valid face ids enumerated from {@code Character.wz/Face} (e.g. {@code 00020000.img} -> 20000). */
    static List<Integer> faceIds() {
        List<Integer> cached = faceIdCache;
        if (cached == null) {
            cached = loadEquipIds("Face");
            faceIdCache = cached;
        }
        return cached;
    }

    /**
     * Cash equips from the equip WZ data, each paired with its worn position. The first call is a
     * single bounded pass over the equip directories; until it completes, provisioning uses no cash
     * outfit rather than blocking on the scan.
     */
    static List<CashEquip> cashCandidates() {
        List<CashEquip> cached = cashCandidateCache;
        if (cached != null) {
            return cached;
        }
        warmCashCandidatesAsync();
        return List.of();
    }

    /** Loads the cash candidates into the cache exactly once (idempotent, thread-safe). */
    static void primeCashCandidates() {
        synchronized (CASH_LOAD_LOCK) {
            if (cashCandidateCache == null) {
                cashCandidateCache = loadCashCandidates();
            }
            cashLoadStarted = true;
        }
    }

    /** Warms the cash cache on a daemon thread so a player's first summon never blocks on WZ I/O. */
    static void warmCashCandidatesAsync() {
        synchronized (CASH_LOAD_LOCK) {
            if (cashLoadStarted) {
                return;
            }
            cashLoadStarted = true;
        }
        final Thread loader = new Thread(CompanionAppearance::primeCashCandidates, "companion-cash-cache");
        loader.setDaemon(true);
        loader.start();
    }

    /** Enumerates the {@code .img} file names in a Character.wz directory as numeric ids. */
    private static List<Integer> loadEquipIds(String directory) {
        final List<Integer> ids = new ArrayList<>();
        try {
            final DataDirectoryEntry root = DataProviderFactory.getDataProvider(WZFiles.CHARACTER).getRoot();
            final DataEntry entry = root.getEntry(directory);
            if (entry instanceof DataDirectoryEntry dir) {
                for (DataFileEntry file : dir.getFiles()) {
                    final int id = parseId(file.getName());
                    if (id > 0) {
                        ids.add(id);
                    }
                }
            }
        } catch (Throwable t) {
            // WZ unavailable: caller falls back to the default face/hair.
        }
        return List.copyOf(ids);
    }

    /**
     * Single pass over the equip directories ({@code Character.wz/<slot>/<id>.img}), reading only
     * {@code info/cash} and {@code info/islot} from the already-loaded data. Deliberately avoids
     * {@code getAllItems()}/{@code getItemData()} which rescan every item per call (O(n^2)).
     */
    static List<CashEquip> loadCashCandidates() {
        final List<CashEquip> candidates = new ArrayList<>();
        try {
            final DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
            final DataDirectoryEntry root = provider.getRoot();
            for (DataDirectoryEntry slotDir : root.getSubdirectories()) {
                final String dirName = slotDir.getName();
                for (DataFileEntry file : slotDir.getFiles()) {
                    final int itemId = parseId(file.getName());
                    if (itemId <= 0 || itemId / 1000000 != 1) {
                        continue; // equips only
                    }
                    final Data img = provider.getData(dirName + "/" + file.getName());
                    final Data info = img == null ? null : img.getChildByPath("info");
                    if (info == null || DataTool.getInt("cash", info, 0) != 1) {
                        continue;
                    }
                    final Byte position = cashPosition(DataTool.getString("islot", info, ""));
                    if (position != null) {
                        candidates.add(new CashEquip(itemId, position));
                    }
                }
            }
        } catch (Throwable t) {
            // No usable WZ data: fall back to no cash outfit.
        }
        return List.copyOf(candidates);
    }

    /** Maps an islot string to its worn cash position, or {@code null} for unsupported slots. */
    static Byte cashPosition(String islot) {
        if (islot == null || islot.isBlank()) {
            return null;
        }
        final EquipSlot slot = EquipSlot.getFromTextSlot(islot);
        final int base = slot.getPrimarySlot();
        if (base >= 0) {
            return null; // unknown islot (getFromTextSlot returns PET_EQUIP with no worn slot)
        }
        final int position = base - 100;
        if (position < MIN_CASH_POSITION || position > MAX_CASH_POSITION) {
            return null;
        }
        return (byte) position;
    }

    private static int parseId(String fileName) {
        if (fileName == null) {
            return -1;
        }
        final int dot = fileName.indexOf('.');
        final String digits = dot >= 0 ? fileName.substring(0, dot) : fileName;
        if (digits.isEmpty() || !digits.matches("\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
