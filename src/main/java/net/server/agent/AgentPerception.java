package net.server.agent;

import client.Character;
import client.Job;
import client.Skill;
import constants.game.GameConstants;
import server.StatEffect;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds {@link AgentObservation}s for an agent character.
 * <p>
 * Perception mode is controlled by {@code LLM_AGENT_PERCEPTION}:
 * <ul>
 *     <li>{@code BOUNDED} (default) — only objects within {@code LLM_AGENT_VIEW_RANGE}
 *     squared of the agent, approximating a real client's view.</li>
 *     <li>{@code OMNISCIENT} — the whole map, for development/debugging only.</li>
 * </ul>
 */
public final class AgentPerception {
    private static final int MAX_OBSERVED = 16;
    private static final int MAX_BUFF_SKILLS = 32;
    private static final int MAX_ATTACK_SKILLS = 32;

    private AgentPerception() {
    }

    public static AgentObservation observe(Character agent) {
        final MapleMap map = agent.getMap();
        final Point pos = agent.getPosition();
        final Job job = agent.getJob();

        final List<AgentObservation.ObservedPlayer> nearby = collectNearbyPlayers(agent, map, pos);
        final List<AgentObservation.ObservedPlayer> party = collectPartyMembers(agent);
        final List<AgentObservation.ObservedMonster> monsters = collectNearbyMonsters(map, pos);
        final List<AgentObservation.ObservedItem> items = collectNearbyItems(map, pos);
        final List<AgentObservation.ObservedNpc> npcs = collectNearbyNpcs(map, pos);

        return new AgentObservation(
                agent.getName(),
                agent.getLevel(),
                job == null ? 0 : job.getId(),
                job == null ? "Unknown" : GameConstants.getJobName(job.getId()),
                agent.getMapId(),
                map.getMapName(),
                map.getStreetName(),
                agent.getHp(),
                agent.getCurrentMaxHp(),
                agent.getMp(),
                agent.getCurrentMaxMp(),
                pos.x,
                pos.y,
                nearby,
                party,
                monsters,
                items,
                npcs,
                collectBuffSkills(agent),
                collectAttackSkills(agent),
                collectHealSkills(agent)
        );
    }

    /** Skill ids the agent has learned that are damaging attacks (usable by AgentCombat). */
    private static List<Integer> collectAttackSkills(Character agent) {
        final List<Integer> result = new ArrayList<>();
        for (Skill skill : agent.getSkills().keySet()) {
            if (!AgentCombat.isAttackSkill(agent, skill.getId())) {
                continue;
            }
            result.add(skill.getId());
            if (result.size() >= MAX_ATTACK_SKILLS) {
                break;
            }
        }
        return result;
    }

    /** Skill ids the agent has learned that are buffs (statups + positive duration). */
    private static List<Integer> collectBuffSkills(Character agent) {
        final List<Integer> result = new ArrayList<>();
        for (Skill skill : agent.getSkills().keySet()) {
            int level = agent.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }
            StatEffect effect = skill.getEffect(level);
            if (effect == null || !effect.isSkill() || effect.getStatups().isEmpty() || effect.getDuration() <= 0) {
                continue;
            }
            result.add(skill.getId());
            if (result.size() >= MAX_BUFF_SKILLS) {
                break;
            }
        }
        return result;
    }

    /** Skill ids the agent has learned that are heal skills it can cast. */
    private static List<Integer> collectHealSkills(Character agent) {
        final List<Integer> result = new ArrayList<>();
        int resolved = AgentSkill.resolveHealSkill(agent);
        if (resolved > 0) {
            result.add(resolved);
        }
        return result;
    }

    private static double rangeSq() {
        return AgentConfig.omniscient() ? Double.POSITIVE_INFINITY : AgentConfig.perceptionRangeSq();
    }

    private static List<AgentObservation.ObservedPlayer> collectNearbyPlayers(Character agent, MapleMap map, Point pos) {
        final List<AgentObservation.ObservedPlayer> result = new ArrayList<>();
        final int partyId = agent.getPartyId();

        final List<MapObject> objects = map.getMapObjectsInRange(pos, rangeSq(), Arrays.asList(MapObjectType.PLAYER));
        for (MapObject object : objects) {
            if (result.size() >= MAX_OBSERVED) {
                break;
            }
            if (!(object instanceof Character other) || other.getId() == agent.getId()) {
                continue;
            }
            result.add(toObserved(other, true, partyId));
        }
        return result;
    }

    private static List<AgentObservation.ObservedPlayer> collectPartyMembers(Character agent) {
        final List<AgentObservation.ObservedPlayer> result = new ArrayList<>();
        for (Character member : agent.getPartyMembersOnline()) {
            if (member.getId() == agent.getId()) {
                continue;
            }
            result.add(toObserved(member, member.getMapId() == agent.getMapId(), agent.getPartyId()));
        }
        return result;
    }

    private static AgentObservation.ObservedPlayer toObserved(Character chr, boolean sameMap, int partyId) {
        final Point chrPos = chr.getPosition();
        final Job job = chr.getJob();
        return new AgentObservation.ObservedPlayer(
                chr.getId(),
                chr.getName(),
                chr.getLevel(),
                job == null ? 0 : job.getId(),
                chr.getMapId(),
                chrPos == null ? 0 : chrPos.x,
                chrPos == null ? 0 : chrPos.y,
                sameMap,
                partyId > 0 && chr.getPartyId() == partyId,
                partyId > 0 && chr.getParty() != null && chr.getParty().getLeaderId() == chr.getId()
        );
    }

    private static List<AgentObservation.ObservedMonster> collectNearbyMonsters(MapleMap map, Point pos) {
        final List<AgentObservation.ObservedMonster> result = new ArrayList<>();
        final List<MapObject> objects = map.getMapObjectsInRange(pos, rangeSq(), Arrays.asList(MapObjectType.MONSTER));
        for (MapObject object : objects) {
            if (result.size() >= MAX_OBSERVED) {
                break;
            }
            if (!(object instanceof Monster monster)) {
                continue;
            }
            final Point monsterPos = monster.getPosition();
            if (monsterPos == null) {
                continue;
            }
            result.add(new AgentObservation.ObservedMonster(
                    monster.getObjectId(),
                    monster.getId(),
                    monster.getName(),
                    monster.getLevel(),
                    monster.getHp(),
                    monster.getMaxHp(),
                    monsterPos.x,
                    monsterPos.y
            ));
        }
        return result;
    }

    private static List<AgentObservation.ObservedItem> collectNearbyItems(MapleMap map, Point pos) {
        final List<AgentObservation.ObservedItem> result = new ArrayList<>();
        final List<MapObject> objects = map.getMapObjectsInRange(pos, rangeSq(), Arrays.asList(MapObjectType.ITEM));
        for (MapObject object : objects) {
            if (result.size() >= MAX_OBSERVED) {
                break;
            }
            if (!(object instanceof MapItem mapItem)) {
                continue;
            }
            final Point itemPos = mapItem.getPosition();
            if (itemPos == null) {
                continue;
            }
            result.add(new AgentObservation.ObservedItem(
                    mapItem.getObjectId(),
                    mapItem.getItemId(),
                    mapItem.getItem() == null ? 0 : mapItem.getItem().getQuantity(),
                    mapItem.getOwnerId(),
                    itemPos.x,
                    itemPos.y
            ));
        }
        return result;
    }

    private static List<AgentObservation.ObservedNpc> collectNearbyNpcs(MapleMap map, Point pos) {
        final List<AgentObservation.ObservedNpc> result = new ArrayList<>();
        final List<MapObject> objects = map.getMapObjectsInRange(pos, rangeSq(), Arrays.asList(MapObjectType.NPC));
        for (MapObject object : objects) {
            if (result.size() >= MAX_OBSERVED) {
                break;
            }
            if (!(object instanceof NPC npc)) {
                continue;
            }
            final Point npcPos = npc.getPosition();
            if (npcPos == null) {
                continue;
            }
            result.add(new AgentObservation.ObservedNpc(
                    npc.getObjectId(),
                    npc.getId(),
                    npcPos.x,
                    npcPos.y
            ));
        }
        return result;
    }
}
