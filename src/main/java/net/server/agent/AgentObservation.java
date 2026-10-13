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
        List<ObservedPlayer> partyMembers,
        List<ObservedMonster> nearbyMonsters,
        List<ObservedItem> nearbyItems,
        List<ObservedNpc> nearbyNpcs,
        List<Integer> buffSkills,
        List<Integer> attackSkills,
        List<Integer> healSkills,
        boolean rangedWeapon,
        int ammoCount,
        boolean outOfAmmo
) {

    /** Backwards-compatible view for observations built before ammo tracking existed. */
    public AgentObservation(
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
            List<ObservedPlayer> partyMembers,
            List<ObservedMonster> nearbyMonsters,
            List<ObservedItem> nearbyItems,
            List<ObservedNpc> nearbyNpcs,
            List<Integer> buffSkills,
            List<Integer> attackSkills,
            List<Integer> healSkills
    ) {
        this(agentName, level, jobId, jobName, mapId, mapName, streetName, hp, maxHp, mp, maxMp, x, y,
                nearbyPlayers, partyMembers, nearbyMonsters, nearbyItems, nearbyNpcs,
                buffSkills, attackSkills, healSkills, false, 0, false);
    }

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

    /** A monster visible to the agent. */
    public record ObservedMonster(
            int objectId,
            int mobId,
            String name,
            int level,
            int hp,
            int maxHp,
            int x,
            int y
    ) {
    }

    /** A dropped item (or meso pile) visible to the agent. */
    public record ObservedItem(
            int objectId,
            int itemId,
            int quantity,
            int ownerId,
            int x,
            int y
    ) {
    }

    /** An NPC visible to the agent. */
    public record ObservedNpc(
            int objectId,
            int npcId,
            int x,
            int y
    ) {
    }
}
