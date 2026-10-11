package net.server.agent;

import java.util.List;

/**
 * An immutable snapshot of everything an agent can currently perceive. Produced by
 * {@link AgentPerception} and handed to the {@link AgentBrain}.
 */
public record AgentObservation(
        String agentName,
        int level,
        int jobId,
        String jobName,
        int mapId,
        String mapName,
        String streetName,
        int hp,
        int maxHp,
        int mp,
        int maxMp,
        int x,
        int y,
        List<ObservedPlayer> nearbyPlayers,
        List<ObservedPlayer> partyMembers
) {

    /** A player visible to (or grouped with) the agent. */
    public record ObservedPlayer(
            int id,
            String name,
            int level,
            int jobId,
            int mapId,
            int x,
            int y,
            boolean sameMap,
            boolean partyMember,
            boolean leader
    ) {
    }
}
