package net.server.agent.harness;

import client.Character;
import client.Job;
import client.QuestStatus;
import client.Stat;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.agent.AgentSession;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flag-gated "cheat" operations for test-harness sessions. These bypass the normal player
 * action path ({@code AgentActionDispatcher}) because they exist to <em>arrange</em>
 * scenarios — spawn a boss, grant a quest item, teleport to a PQ map — not to test them.
 * Every gameplay action the test is <em>verifying</em> still goes through the real handlers
 * via {@code AgentSession#applyExternalActions}.
 * <p>
 * All ops are guarded by {@link TestHarnessConfig#enabled()}: on a vanilla server
 * ({@code USE_TEST_HARNESS: false}) every method throws and nothing can be mutated.
 */
public final class TestOps {
    private TestOps() {
    }

    private static void requireEnabled() {
        if (!TestHarnessConfig.enabled()) {
            throw new IllegalStateException("Test harness is disabled (USE_TEST_HARNESS: false)");
        }
    }

    private static Character character(AgentSession session) {
        requireEnabled();
        Character chr = session.getPlayer();
        if (chr == null) {
            throw new IllegalArgumentException("Session has no character (not logged in?)");
        }
        return chr;
    }

    private static MapleMap map(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            throw new IllegalStateException("Character has no current map");
        }
        return map;
    }

    /** Warps the session's character to {@code mapId} (optionally through a named portal). */
    public static Map<String, Object> teleport(AgentSession session, int mapId, String portalName) {
        Character chr = character(session);
        MapleMap target = chr.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (target == null) {
            throw new IllegalArgumentException("Unknown map id " + mapId);
        }
        if (portalName == null || portalName.isBlank()) {
            chr.changeMap(target);
        } else {
            chr.changeMap(mapId, portalName);
        }
        return result("warped to map %d (%s) at %s", mapId, target.getMapName(), chr.getPosition());
    }

    /** Spawns a monster on the session's map and returns its object id (for Attack/SkillAttack). */
    public static Map<String, Object> spawnMob(AgentSession session, int mobId, int x, int y) {
        Character chr = character(session);
        MapleMap map = map(chr);
        Monster mob = LifeFactory.getMonster(mobId);
        if (mob == null) {
            throw new IllegalArgumentException("Unknown mob id " + mobId);
        }
        map.spawnMonsterOnGroundBelow(mob, new Point(x, y));
        Map<String, Object> out = result("spawned mob %d on map %d", mobId, map.getId());
        out.put("objectId", mob.getObjectId());
        out.put("name", ItemInformationProvider.getInstance().getName(mobId));
        return out;
    }

    /** Kills a monster by object id. {@code withDrops=true} makes normal drops spawn. */
    public static Map<String, Object> killMob(AgentSession session, int objectId, boolean withDrops) {
        Character chr = character(session);
        MapleMap map = map(chr);
        Monster mob = map.getMonsterByOid(objectId);
        if (mob == null) {
            throw new IllegalArgumentException("No monster with objectId " + objectId + " on map " + map.getId());
        }
        map.killMonster(mob, chr, withDrops);
        return result("killed objectId %d (mob %d) on map %d, withDrops=%s",
                objectId, mob.getId(), map.getId(), withDrops);
    }

    /** Lists monsters currently on the session's map (id, object id, hp, position). */
    public static Map<String, Object> listMobs(AgentSession session) {
        Character chr = character(session);
        MapleMap map = map(chr);
        var mobs = map.getAllMonsters().stream()
                .map(m -> {
                    Map<String, Object> mo = new LinkedHashMap<>();
                    mo.put("objectId", m.getObjectId());
                    mo.put("mobId", m.getId());
                    mo.put("hp", m.getHp());
                    mo.put("x", m.getPosition().x);
                    mo.put("y", m.getPosition().y);
                    return mo;
                })
                .toList();
        Map<String, Object> out = result("%d monster(s) on map %d", mobs.size(), map.getId());
        out.put("mobs", mobs);
        return out;
    }

    /** Grants an item straight into the character's inventory (no drop, no shop). */
    public static Map<String, Object> grantItem(AgentSession session, int itemId, int quantity) {
        Character chr = character(session);
        short qty = (short) Math.max(1, Math.min(quantity, Short.MAX_VALUE));
        boolean ok = InventoryManipulator.addById(chr.getClient(), itemId, qty, "", -1);
        if (!ok) {
            throw new IllegalStateException("Failed to add item " + itemId + " x" + qty + " (inventory full?)");
        }
        return result("granted item %d x%d to %s", itemId, qty, chr.getName());
    }

    /** Grants mesos (negative amounts remove). */
    public static Map<String, Object> grantMesos(AgentSession session, int amount) {
        Character chr = character(session);
        chr.gainMeso(amount, false);
        return result("granted %d mesos to %s", amount, chr.getName());
    }

    /** Sets the character's level (recalculates HP/MP ceilings; does not grant SP/AP). */
    public static Map<String, Object> setLevel(AgentSession session, int level) {
        Character chr = character(session);
        if (level < 1 || level > 200) {
            throw new IllegalArgumentException("Level must be 1..200, got " + level);
        }
        chr.setLevel(level);
        chr.updateSingleStat(Stat.LEVEL, level);
        chr.addHP(chr.getCurrentMaxHp());
        chr.addMP(chr.getCurrentMaxMp());
        return result("set %s to level %d", chr.getName(), level);
    }

    /** Sets a quest's status. Valid states: "not_started", "started", "completed". */
    public static Map<String, Object> setQuestState(AgentSession session, int questId, String state) {
        Character chr = character(session);
        QuestStatus.Status status = switch (state == null ? "" : state.strip().toLowerCase()) {
            case "not_started", "0" -> QuestStatus.Status.NOT_STARTED;
            case "started", "1" -> QuestStatus.Status.STARTED;
            case "completed", "2" -> QuestStatus.Status.COMPLETED;
            default -> throw new IllegalArgumentException(
                    "Invalid quest state '" + state + "' (use not_started | started | completed)");
        };
        QuestStatus qs = chr.getQuest(questId);
        qs.setStatus(status);
        chr.updateQuestStatus(qs);
        return result("quest %d set to %s for %s", questId, state, chr.getName());
    }

    /** Spawns an item drop on the session's map (FFA, owned by the session character). */
    public static Map<String, Object> spawnDrop(AgentSession session, int itemId, int quantity, int x, int y) {
        Character chr = character(session);
        MapleMap map = map(chr);
        short qty = (short) Math.max(1, Math.min(quantity, Short.MAX_VALUE));
        Item item = new Item(itemId, (short) 0, qty);
        Point pos = new Point(x, y);
        map.spawnItemDrop(chr, chr, item, pos, true, false);
        return result("spawned drop item %d x%d at (%d,%d) on map %d", itemId, qty, x, y, map.getId());
    }

    /** Grants NX/maple points to the character's cash shop. Types: 1=Nx credit, 2=Maple point, 4=Nx prepaid. */
    public static Map<String, Object> grantCash(AgentSession session, int amount, int type) {
        Character chr = character(session);
        chr.getCashShop().gainCash(type, amount);
        return result("granted %d cash (type %d) to %s", amount, type, chr.getName());
    }

    /** Fully heals the session character (HP/MP to max). */
    public static Map<String, Object> heal(AgentSession session) {
        Character chr = character(session);
        chr.addHP(chr.getCurrentMaxHp());
        chr.addMP(chr.getCurrentMaxMp());
        return result("healed %s (hp=%d/%d mp=%d/%d)", chr.getName(),
                chr.getHp(), chr.getCurrentMaxHp(), chr.getMp(), chr.getCurrentMaxMp());
    }

    /** Convenience for the MCP layer: which jobs a session may be provisioned as. */
    public static boolean isValidJob(int jobId) {
        return Job.getById(jobId) != null;
    }

    private static Map<String, Object> result(String format, Object... args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", String.format(format, args));
        return out;
    }
}
