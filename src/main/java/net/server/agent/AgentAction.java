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

    // ----------------------------------------------------------------
    // PlayerInteraction rooms (minigames & hired merchants)
    // ----------------------------------------------------------------

    /**
     * Create a minigame room (Omok or MatchCard) on the agent's map. Requires the matching
     * item: Omok board {@code 4080000 + pieceType} (pieceType 0..11), MatchCard 4080100
     * (pieceType 0..2 selects 4x3/5x4/6x5). The creator becomes the room owner.
     */
    record MiniGameCreate(String gameType, String description, String password, int pieceType) implements AgentAction {
    }

    /**
     * Visit a room on the agent's map owned by {@code ownerName} — a minigame (Omok /
     * MatchCard), a player shop or a hired merchant (resolved by scanning map room objects).
     */
    record RoomVisit(String ownerName, String password) implements AgentAction {
    }

    /** Owner: start a match in the agent's minigame room (visitor must already have joined). */
    record MiniGameStart() implements AgentAction {
    }

    /** Place an Omok piece at board coordinates (0..14 each axis); {@code piece} is 1 (owner) or 2 (visitor). */
    record MiniGameMoveOmok(int x, int y, int piece) implements AgentAction {
    }

    /** MatchCard: flip a card. {@code turn} 1 = first card of the turn, 0 = second card. */
    record MiniGameSelectCard(int turn, int slot) implements AgentAction {
    }

    /** Forfeit the current minigame match (opponent wins). */
    record MiniGameGiveUp() implements AgentAction {
    }

    /** Propose a tie in the current minigame match (opponent answers with {@link MiniGameTieAnswer}). */
    record MiniGameTieRequest() implements AgentAction {
    }

    /** Answer a tie proposal: {@code accept=true} ends the match as a draw, false denies it. */
    record MiniGameTieAnswer(boolean accept) implements AgentAction {
    }

    /** Leave the current room (minigame, player shop or hired merchant) via the generic EXIT packet. */
    record RoomExit() implements AgentAction {
    }

    /**
     * Open a hired merchant room in maintenance mode. Requires a hired-merchant item
     * (503xxxx) in the CASH inventory, a free-market room map and no other store nearby.
     * Follow with {@link HiredMerchantPutItem} to stock items and {@link HiredMerchantOpen}
     * to open to visitors.
     */
    record HiredMerchantCreate(String description, int itemId) implements AgentAction {
    }

    /** Owner (maintenance mode): list an item for sale — {@code bundles} × {@code perBundle} units at {@code price} mesos each bundle. */
    record HiredMerchantPutItem(String inventoryType, short slot, short bundles, short perBundle,
                                int price) implements AgentAction {
    }

    /** Owner: open the merchant to visitors (spawns the merchant box on the map). */
    record HiredMerchantOpen() implements AgentAction {
    }

    /** Visitor: buy the item at 0-based shop-listing index; {@code quantity} is the number of bundles. */
    record HiredMerchantBuy(int itemIndex, short quantity) implements AgentAction {
    }

    /** Owner: withdraw the mesos accumulated from sales (credited on each buyer purchase). */
    record HiredMerchantWithdrawMesos() implements AgentAction {
    }

    /** Owner (maintenance mode): take the item back from the shop listing at 0-based {@code slot}. */
    record HiredMerchantTakeItemBack(int slot) implements AgentAction {
    }

    /** Owner (maintenance mode — visit your own merchant box first): close the merchant permanently; items return to inventory. */
    record HiredMerchantClose() implements AgentAction {
    }

    // ----------------------------------------------------------------
    // Guild / alliance / marriage / family (social systems)
    // ----------------------------------------------------------------

    /**
     * Request guild creation with the given name (GUILD_OPERATION 0x02). Requires
     * {@code CREATE_GUILD_MIN_PARTNERS} eligible co-founders (no guild, no party) on the
     * leader's map and {@code CREATE_GUILD_COST} mesos; starts a match-checker
     * confirmation. Each co-founder answers with {@link GuildCreateAnswer}; the guild is
     * created once every invitee accepted (leader included implicitly).
     */
    record GuildCreate(String name) implements AgentAction {
    }

    /**
     * Answer a pending guild-creation confirmation (GUILD_OPERATION 0x1E). The last
     * accepting co-founder triggers the actual guild creation (leader at Guild HQ).
     */
    record GuildCreateAnswer(boolean accept) implements AgentAction {
    }

    /** Invite a player (by name) to the agent's guild (GUILD_OPERATION 0x05). */
    record GuildInvite(String targetName) implements AgentAction {
    }

    /** Accept a pending guild invitation from {@code inviterName} (GUILD_OPERATION 0x06). */
    record GuildAcceptInvite(String inviterName) implements AgentAction {
    }

    /** Leave the agent's guild (GUILD_OPERATION 0x07; {@code name} must match the agent). */
    record GuildLeave(String name) implements AgentAction {
    }

    /** Expel a member (rank 1/2 only) by character id and name (GUILD_OPERATION 0x08). */
    record GuildExpel(int targetId, String targetName) implements AgentAction {
    }

    /**
     * Rename the five guild rank titles (rank 1 only; GUILD_OPERATION 0x0D).
     * {@code ranks} must contain exactly 5 entries (index 0 = master).
     */
    record GuildChangeRankTitle(List<String> ranks) implements AgentAction {
    }

    /** Change a member's guild rank (GUILD_OPERATION 0x0E; rank 1/2 permission rules apply). */
    record GuildChangeRank(int targetId, int newRank) implements AgentAction {
    }

    /** Set the guild notice (GUILD_OPERATION 0x10). */
    record GuildNotice(String notice) implements AgentAction {
    }

    /**
     * Invite another guild (by name) to the agent's alliance (ALLIANCE_OPERATION 0x03).
     * The target guild's master receives the invitation.
     */
    record AllianceInvite(String guildName) implements AgentAction {
    }

    /**
     * Accept a pending alliance invitation (ALLIANCE_OPERATION 0x04); the guild master's
     * guild joins the alliance the inviter belongs to. Alliance creation/disbanding itself is
     * driven through the Lenario NPC (2010009) with the dialog actions.
     */
    record AllianceAcceptInvite(String inviterName) implements AgentAction {
    }

    /** Remove the agent's guild from its alliance (ALLIANCE_OPERATION 0x02; guild rank 1). */
    record AllianceLeave() implements AgentAction {
    }

    /**
     * Propose engagement to a player on the same map (RING_ACTION 0). Both parties must be
     * level 50+, of opposite gender, unmarried and ring-free; the proposer must hold an
     * engagement box item (2240000..2240015) granted via {@code setup grant_item}.
     */
    record Engage(String targetName, int itemId) implements AgentAction {
    }

    /**
     * Answer a pending engagement proposal (RING_ACTION 2). On accept both characters gain
     * the empty engagement box in ETC and {@code partnerId} is set for both.
     */
    record EngageAnswer(boolean accept, String proposerName) implements AgentAction {
    }

    /**
     * Break an engagement or divorce: drop the engagement token / wedding ring item
     * (RING_ACTION 3). Requires the token (4031357+) or wedding ring (1112803x) in the
     * inventory; resets {@code partnerId} on both sides.
     */
    record BreakEngagement(int itemId) implements AgentAction {
    }

    /**
     * Invite a player on the same map (levels within 20, target above 10) into the family
     * system as a junior (ADD_FAMILY). The invitee answers with {@link FamilyAccept}.
     */
    record FamilyAdd(String name) implements AgentAction {
    }

    /** Answer a pending family invitation (ACCEPT_FAMILY): accept joins the inviter's family. */
    record FamilyAccept(boolean accept, String inviterName) implements AgentAction {
    }

    /** Do nothing this cycle. */
    record Idle() implements AgentAction {
    }
}
