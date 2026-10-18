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
        Character chr = session.getPlayer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getCharacterId());
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
        out.put("items", obs.nearbyItems());
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

        List<Object> events = new ArrayList<>();
        for (AgentEvent event : drainedEvents) {
            events.add(event.toString());
        }
        out.put("recentEvents", events);
        return out;
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
