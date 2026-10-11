package net.server.agent;

import client.Character;
import client.Job;
import constants.game.GameConstants;
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
    private AgentPerception() {
    }

    public static AgentObservation observe(Character agent) {
        final MapleMap map = agent.getMap();
        final Point pos = agent.getPosition();
        final Job job = agent.getJob();

        final List<AgentObservation.ObservedPlayer> nearby = collectNearbyPlayers(agent, map, pos);
        final List<AgentObservation.ObservedPlayer> party = collectPartyMembers(agent);

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
                party
        );
    }

    private static List<AgentObservation.ObservedPlayer> collectNearbyPlayers(Character agent, MapleMap map, Point pos) {
        final List<AgentObservation.ObservedPlayer> result = new ArrayList<>();
        final int partyId = agent.getPartyId();

        final double rangeSq = AgentConfig.omniscient() ? Double.POSITIVE_INFINITY : AgentConfig.perceptionRangeSq();
        final List<MapObject> objects = map.getMapObjectsInRange(pos, rangeSq, Arrays.asList(MapObjectType.PLAYER));
        for (MapObject object : objects) {
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
}
