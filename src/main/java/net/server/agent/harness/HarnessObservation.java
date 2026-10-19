package net.server.agent.harness;

import client.Character;
import client.Job;
import client.QuestStatus;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.agent.AgentEvent;
import net.server.agent.AgentObservation;
import net.server.agent.AgentPerception;
import net.server.agent.AgentSession;
import server.ItemInformationProvider;
import server.maps.MapItem;
import server.maps.MapObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON-friendly observation payload returned by the harness {@code observe} tool.
 * Extends the autonomous-agent perception ({@link AgentPerception}) with the extra state a
 * tester needs: mesos, exp, equipment, inventory, quest statuses and buffered harness events.
 */
public final class HarnessObservation {
    private HarnessObservation() {
    }

    /** Full observation for a harness session. */
    public static Map<String, Object> describe(AgentSession session, List<AgentEvent> drainedEvents) {
        return describe(session, drainedEvents, false);
    }

    /** Full observation; {@code omniscient} lifts the view-range cap (whole map). */
    public static Map<String, Object> describe(AgentSession session, List<AgentEvent> drainedEvents,
                                               boolean omniscient) {
        java.util.function.Supplier<Map<String, Object>> build = () -> buildDescription(session, drainedEvents);
        return omniscient ? AgentPerception.withOmniscient(build) : build.get();
    }

    private static Map<String, Object> buildDescription(AgentSession session, List<AgentEvent> drainedEvents) {
        Character chr = session.getPlayer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getCharacterId());
        out.put("channel", session.getClient().getChannel());
        out.put("characterName", session.getName());
        if (chr == null) {
            out.put("error", "session character not loaded");
            return out;
        }

        AgentObservation obs = AgentPerception.observe(chr);
        out.put("level", obs.level());
        out.put("jobId", obs.jobId());
        out.put("jobName", obs.jobName());
        out.put("mapId", obs.mapId());
        out.put("mapName", obs.mapName());
        out.put("streetName", obs.streetName());
        out.put("hp", obs.hp());
        out.put("maxHp", obs.maxHp());
        out.put("mp", obs.mp());
        out.put("maxMp", obs.maxMp());
        out.put("x", obs.x());
        out.put("y", obs.y());
        out.put("mesos", chr.getMeso());
        out.put("exp", chr.getExp());
        out.put("alive", chr.isAlive());
        out.put("nearbyPlayers", obs.nearbyPlayers());
        out.put("partyMembers", obs.partyMembers());
        out.put("monsters", obs.nearbyMonsters());
        out.put("items", describeDrops(chr, obs.nearbyItems()));
        out.put("npcs", obs.nearbyNpcs());
        out.put("buffSkills", obs.buffSkills());
        out.put("attackSkills", obs.attackSkills());
        out.put("healSkills", obs.healSkills());
        out.put("rangedWeapon", obs.rangedWeapon());
        out.put("ammoCount", obs.ammoCount());
        out.put("outOfAmmo", obs.outOfAmmo());

        out.put("equipment", describeEquipment(chr));
        out.put("inventory", describeInventory(chr));
        out.put("quests", describeQuests(chr));
        out.put("npcDialog", describeNpcDialog(session));
        out.put("storage", describeStorage(chr, session));
        out.put("buddies", describeBuddies(chr));
        out.put("cashShop", describeCashShop(chr));
        out.put("trade", describeTrade(chr));

        List<Object> events = new ArrayList<>();
        for (AgentEvent event : drainedEvents) {
            events.add(event.toString());
        }
        out.put("recentEvents", events);
        return out;
    }

    /**
     * Enriches observed drops with the metadata an agent needs to understand pickup rules:
     * item name, owner, and the gating quest id (drops bound to a quest are only pickable by
     * characters on that quest — the server refuses silently, like for a real client).
     */
    private static List<Object> describeDrops(Character chr, List<AgentObservation.ObservedItem> items) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Object> out = new ArrayList<>();
        for (AgentObservation.ObservedItem item : items) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("objectId", item.objectId());
            e.put("itemId", item.itemId());
            e.put("name", ii.getName(item.itemId()));
            e.put("quantity", item.quantity());
            e.put("ownerId", item.ownerId());
            e.put("x", item.x());
            e.put("y", item.y());
            MapObject ob = chr.getMap() == null ? null : chr.getMap().getMapObject(item.objectId());
            if (ob instanceof MapItem mapItem) {
                int questId = mapItem.getQuest();
                e.put("questId", questId > 0 ? questId : null);
                e.put("pickedUp", mapItem.isPickedUp());
            }
            out.add(e);
        }
        return out;
    }

    /** The currently open NPC dialog (null when none). Drives NpcSelect/NpcRespondText. */
    private static Map<String, Object> describeNpcDialog(AgentSession session) {
        if (session.getNpcDialogMsgType() < 0) {
            return null;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("npcId", session.getNpcDialogNpcId());
        e.put("msgType", session.getNpcDialogMsgType());
        e.put("type", HarnessDialogDecoder.typeName(session.getNpcDialogMsgType()));
        e.put("text", session.getNpcDialogText());
        e.put("open", session.isNpcDialogOpen());
        e.put("disposed", session.isNpcDialogDisposed());
        return e;
    }

    /** Account-storage state: UI-open flag, slots, mesos and stored items. */
    private static Map<String, Object> describeStorage(Character chr, AgentSession session) {
        var storage = chr.getStorage();
        if (storage == null) {
            return null;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("opened", session.isStorageOpened());
        e.put("slots", storage.getSlots());
        e.put("mesos", storage.getMeso());
        List<Object> items = new ArrayList<>();
        for (Item item : storage.getItems()) {
            Map<String, Object> ie = new LinkedHashMap<>();
            ie.put("itemId", item.getItemId());
            ie.put("quantity", item.getQuantity());
            ie.put("inventoryType", item.getInventoryType().name());
            items.add(ie);
        }
        e.put("items", items);
        return e;
    }

    /** Buddy list entries (name, online channel). */
    private static List<Object> describeBuddies(Character chr) {
        List<Object> out = new ArrayList<>();
        for (var ble : chr.getBuddylist().getBuddies()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", ble.getName());
            e.put("characterId", ble.getCharacterId());
            e.put("online", ble.getChannel() > 0);
            out.add(e);
        }
        return out;
    }

    /** Cash shop state: open flag, balances and owned cash inventory item ids. */
    private static Map<String, Object> describeCashShop(Character chr) {
        Map<String, Object> e = new LinkedHashMap<>();
        var cs = chr.getCashShop();
        e.put("opened", cs.isOpened());
        e.put("nxCredit", cs.getCash(server.CashShop.NX_CREDIT));
        e.put("maplePoint", cs.getCash(server.CashShop.MAPLE_POINT));
        e.put("nxPrepaid", cs.getCash(server.CashShop.NX_PREPAID));
        List<Object> items = new ArrayList<>();
        for (Item item : cs.getInventory()) {
            Map<String, Object> ie = new LinkedHashMap<>();
            ie.put("itemId", item.getItemId());
            ie.put("quantity", item.getQuantity());
            items.add(ie);
        }
        e.put("items", items);
        return e;
    }

    /** Open trade state (null when not trading): partner, staged items, mesos, confirm flags. */
    private static Map<String, Object> describeTrade(Character chr) {
        var trade = chr.getTrade();
        if (trade == null) {
            return null;
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("partnerName", trade.getPartner() != null ? trade.getPartner().getChr().getName() : null);
        e.put("fullTrade", trade.isFullTrade());
        e.put("mesos", trade.getMeso());
        List<Object> items = new ArrayList<>();
        for (Item item : trade.getItems()) {
            Map<String, Object> ie = new LinkedHashMap<>();
            ie.put("itemId", item.getItemId());
            ie.put("quantity", item.getQuantity());
            items.add(ie);
        }
        e.put("items", items);
        return e;
    }

    private static List<Map<String, Object>> describeEquipment(Character chr) {
        List<Map<String, Object>> equipped = new ArrayList<>();
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).listCopy()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("itemId", item.getItemId());
            e.put("slot", item.getPosition());
            e.put("quantity", item.getQuantity());
            equipped.add(e);
        }
        return equipped;
    }

    private static Map<String, Object> describeInventory(Character chr) {
        Map<String, Object> inv = new LinkedHashMap<>();
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC, InventoryType.CASH)) {
            Inventory inventory = chr.getInventory(type);
            if (inventory == null) {
                continue;
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Item item : inventory.listCopy()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("itemId", item.getItemId());
                e.put("quantity", item.getQuantity());
                e.put("slot", item.getPosition());
                entries.add(e);
            }
            inv.put(type.name().toLowerCase(), entries);
        }
        return inv;
    }

    private static Map<String, Object> describeQuests(Character chr) {
        Map<String, Object> quests = new LinkedHashMap<>();
        List<Object> started = new ArrayList<>();
        List<Object> completed = new ArrayList<>();
        for (QuestStatus qs : chr.getQuests()) {
            int id = qs.getQuest().getId();
            switch (qs.getStatus()) {
                case STARTED -> started.add(id);
                case COMPLETED -> completed.add(id);
                default -> {
                }
            }
        }
        quests.put("started", started);
        quests.put("completed", completed);
        return quests;
    }

    /** Human-readable job label for logs. */
    public static String jobName(int jobId) {
        Job job = Job.getById(jobId);
        return job == null ? "Unknown(" + jobId + ")" : job.name();
    }
}
