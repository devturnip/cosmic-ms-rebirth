package net.server.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * A high-level action an agent wants to perform. Actions are executed by
 * {@link AgentActionDispatcher}, which translates them into the same inbound packets (or
 * server-side calls) a real client would produce, so normal validation still applies.
 */
public sealed interface AgentAction {

    /** Speak in general (map) chat. */
    record Say(String message) implements AgentAction {
    }

    /** Speak in party chat. */
    record PartySay(String message) implements AgentAction {
    }

    /** Whisper another player. */
    record Whisper(String targetName, String message) implements AgentAction {
    }

    /** Invite a nearby player to the agent's party. */
    record InviteToParty(String targetName) implements AgentAction {
    }

    /** Accept a pending party invitation. */
    record AcceptPartyInvite(int partyId) implements AgentAction {
    }

    /** Deny a pending party invitation. */
    record DenyPartyInvite(String inviterName) implements AgentAction {
    }

    /** Leave the current party. */
    record LeaveParty() implements AgentAction {
    }

    /** Walk the agent to a position on the current map. */
    record MoveTo(int x, int y) implements AgentAction {
    }

    /** Stick to a player by name (sticky manual follow). */
    record Follow(String targetName) implements AgentAction {
    }

    /** Stop following the current follow target. */
    record StopFollowing() implements AgentAction {
    }

    /** Pick up a dropped item by its map object id. */
    record PickupItem(int objectId) implements AgentAction {
    }

    /** Use a consumable from the USE inventory (e.g. a potion) by item id. */
    record UseItem(int itemId) implements AgentAction {
    }

    /** Respawn at the map's return point after death. */
    record Respawn() implements AgentAction {
    }

    /** Perform a basic melee attack on a monster by its map object id. */
    record Attack(int targetObjectId) implements AgentAction {
    }

    /** Attack a monster with a learned damaging skill. */
    record SkillAttack(int targetObjectId, int skillId) implements AgentAction {
    }

    /**
     * Attack several monsters at once with a learned damaging skill. Honours
     * {@code LLM_AGENT_AOE_ATTACK}: when multi-target attacks are disabled, or the skill is
     * unusable, only the first target is attacked (with the skill, or a basic attack).
     */
    record SkillAttackMany(List<Integer> targetObjectIds, int skillId) implements AgentAction {
        private static final int MAX_TARGETS = 15;

        public SkillAttackMany {
            List<Integer> copy = new ArrayList<>();
            if (targetObjectIds != null) {
                for (Integer id : targetObjectIds) {
                    if (id == null || copy.contains(id)) {
                        continue;
                    }
                    copy.add(id);
                    if (copy.size() >= MAX_TARGETS) {
                        break;
                    }
                }
            }
            targetObjectIds = List.copyOf(copy);
        }
    }

    /** Recharge ranged projectiles from a nearby NPC (arrows/bullets/throwing stars). */
    record RechargeAmmo() implements AgentAction {
    }

    /** Cast a buff skill the agent has learned. */
    record CastBuff(int skillId) implements AgentAction {
    }

    /** Cast a heal skill to restore the agent and/or nearby party members. */
    record CastHeal(int skillId) implements AgentAction {
    }

    /** Persist a durable memory fact. */
    record Remember(String fact) implements AgentAction {
    }

    /** Open a dialog with an NPC by its npc id (resolved to an object on the agent's map). */
    record NpcTalk(int npcId) implements AgentAction {
    }

    /**
     * Respond to the pending NPC dialog with a selection (menu option, yes=1/no=0,
     * accept=1/decline=0, number answer). Requires an open dialog (see {@code NpcTalk}).
     */
    record NpcSelect(int selection) implements AgentAction {
    }

    /** Respond to a pending text-input dialog (msgType 2). */
    record NpcRespondText(String text) implements AgentAction {
    }

    /** End the currently open NPC dialog (as if the agent pressed Esc / End Chat). */
    record NpcEndDialog() implements AgentAction {
    }

    /** Do nothing this cycle. */
    record Idle() implements AgentAction {
    }
}
