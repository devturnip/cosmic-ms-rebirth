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

    /**
     * Start a scripted quest through its quest script (the real client's QUEST_ACTION path),
     * with {@code npcId} nearby. The script's dialog frames flow through the normal dialog
     * actions ({@code NpcSelect}/{@code NpcRespondText}).
     */
    record QuestStart(int questId, int npcId) implements AgentAction {
    }

    /** Complete a scripted quest through its quest script (same path as {@link QuestStart}). */
    record QuestComplete(int questId, int npcId) implements AgentAction {
    }

    /** Forfeit a started quest (QUEST_ACTION 3). */
    record QuestForfeit(int questId) implements AgentAction {
    }

    /** Add another player to the buddy list (they receive a request; auto-accepted if mutual-free). */
    record AddBuddy(String name) implements AgentAction {
    }

    /** Enter the cash shop (the character leaves the channel map until exit/end of session). */
    record EnterCashShop() implements AgentAction {
    }

    /** Buy a cash item (real CashOperationHandler validation). {@code useNX}: 0 = cash, 1 = maple points. */
    record CashBuy(int useNX, int snCS) implements AgentAction {
    }

    /** Invite another player on the same map to a trade (by character name). */
    record TradeInvite(String targetName) implements AgentAction {
    }

    /** Accept a pending trade invite (the invitee's next step after {@code TradeInvite}). */
    record TradeAccept() implements AgentAction {
    }

    /** Put an item into the open trade (inventory type name e.g. "USE", slot = inventory slot). */
    record TradePutItem(String inventoryType, short slot, short quantity, byte targetSlot) implements AgentAction {
    }

    /** Put mesos into the open trade. */
    record TradePutMesos(int amount) implements AgentAction {
    }

    /** Confirm the open trade; the exchange completes when both sides confirmed. */
    record TradeConfirm() implements AgentAction {
    }

    /** Store an item into the account storage (requires a storage NPC's UI to be open, level 15+). */
    record StorageStore(short slot, int itemId, short quantity) implements AgentAction {
    }

    /** Take an item out of the account storage (slot is the storage's 0-based slot for the type). */
    record StorageTakeOut(String inventoryType, byte slot) implements AgentAction {
    }

    /** Move mesos to/from the storage: positive withdraws from storage, negative stores. */
    record StorageMesos(int amount) implements AgentAction {
    }

    /** Close the storage UI. */
    record StorageClose() implements AgentAction {
    }

    /** Send a Duey parcel: item from the given inventory slot plus mesos, to another character. */
    record DueySend(String inventoryType, short slot, short quantity, int mesos, String recipient,
                    String message) implements AgentAction {
    }

    /** Refresh/claim Duey state: op 0 re-lists packages; a nonzero packageId claims that parcel. */
    record DueyAction(int operation, int packageId) implements AgentAction {
    }

    /** Enter a map portal by name — the only way to traverse scripted portals (e.g. PQ stages). */
    record EnterPortal(String portalName) implements AgentAction {
    }

    /** Do nothing this cycle. */
    record Idle() implements AgentAction {
    }
}
