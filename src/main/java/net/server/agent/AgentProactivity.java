package net.server.agent;

import client.Character;
import net.server.world.Party;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Opt-in proactive (non-reactive) behaviors: greeting nearby players, staying listed for
 * party-search matchmaking, and patrolling a map when idle. All of these are gated behind
 * {@code LLM_AGENT_PROACTIVE} plus a per-behavior switch, are throttled per agent, and never
 * invoke the LLM (a greeting is a fixed/persona line), so they add no request cost.
 */
public final class AgentProactivity {

    private AgentProactivity() {
    }

    /** Greets the nearest player within perception range that has not been greeted recently. */
    public static boolean greet(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null || chr.getPosition() == null) {
            return false;
        }
        long cooldown = AgentConfig.greetCooldownMs();
        session.pruneGreetings(cooldown);
        double range = AgentConfig.perceptionRangeSq();
        Character nearest = null;
        double bestDistance = Double.MAX_VALUE;
        // Selection is read-only; only the chosen player is marked (otherwise players closer than
        // the current best would be silently suppressed without ever being greeted).
        for (Character other : chr.getMap().getAllPlayers()) {
            if (other.getId() == chr.getId() || AgentManager.getInstance().isAgent(other.getId())) {
                continue; // do not greet ourselves or other agents
            }
            if (other.isGM() || other.isHidden() || other.getPosition() == null) {
                continue; // never name a hidden/GM player in public chat
            }
            double distance = chr.getPosition().distanceSq(other.getPosition());
            if (distance <= range && distance < bestDistance && session.isGreetDue(other.getId(), cooldown)) {
                bestDistance = distance;
                nearest = other;
            }
        }
        if (nearest == null) {
            return false;
        }
        String greeting = AgentConfig.greetingTemplate();
        if (greeting == null || greeting.isBlank()) {
            greeting = session.getBrain().greeting();
        }
        // Mark only after the greeting was actually sent (it may be muted or rate-limited).
        if (session.perform(new AgentAction.Say(greeting.replace("{name}", nearest.getName())))) {
            session.markGreeted(nearest.getId());
            return true;
        }
        return false;
    }

    /**
     * Keeps the agent listed with the party-search coordinator so solo players can be matched
     * with it automatically. Only the leader of a non-full party (or a party-less agent) is
     * registered; {@link AgentManager#registerForPartySearch} creates a party if needed.
     */
    public static boolean matchmake(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null || chr.getLevel() < 10) {
            return false;
        }
        if (session.getFollowTargetId() >= 0) {
            return false; // already paired with a follow target; do not match-make
        }
        Party party = chr.getParty();
        if (party != null && (party.getMembers().size() >= 6 || party.getLeaderId() != chr.getId())) {
            return false; // full, or not the leader (cannot list it)
        }
        if (!session.markMatchmakeDue(AgentConfig.matchmakeIntervalMs())) {
            return false;
        }
        int level = chr.getLevel();
        return AgentManager.getInstance().registerForPartySearch(session, Math.max(10, level - 10), level + 10, Integer.MAX_VALUE);
    }

    /** Wanders toward a random point on the map when idle (not following, no monster nearby). */
    public static boolean patrol(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null || chr.getPosition() == null) {
            return false;
        }
        if (session.getFollowTargetId() >= 0) {
            return false; // following takes priority over wandering
        }
        if (AgentCombat.nearestMonster(chr, Math.max(AgentConfig.attackRangeSq(), 250000.0)) != null) {
            return false; // do not wander into (or away from) a fight
        }
        if (!session.markPatrolDue(AgentConfig.patrolIntervalMs())) {
            return false;
        }
        Point current = chr.getPosition();
        Rectangle area = chr.getMap().getMapArea();
        int minX;
        int maxX;
        if (area != null && area.width > 0) {
            minX = area.x;
            maxX = area.x + area.width;
        } else {
            minX = current.x - 400;
            maxX = current.x + 400;
        }
        if (maxX <= minX) {
            return false;
        }
        if (maxX == Integer.MAX_VALUE) {
            maxX = Integer.MAX_VALUE - 1; // avoid overflow on the inclusive bound below
        }
        int targetX = ThreadLocalRandom.current().nextInt(minX, maxX + 1);
        AgentMovement.moveToSmart(session, new Point(targetX, current.y), AgentMovement.MAX_STEP);
        return true;
    }
}
