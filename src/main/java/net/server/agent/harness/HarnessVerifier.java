package net.server.agent.harness;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.Server;
import net.server.agent.AgentAction;
import net.server.agent.AgentActionDispatcher;
import net.server.agent.AgentSession;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.guild.Guild;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import server.maps.HiredMerchant;
import server.maps.MapItem;
import server.maps.MiniGame;
import server.maps.MapObject;
import server.maps.PlayerShopItem;
import server.maps.Reactor;

import java.awt.Point;
import java.util.Locale;

/**
 * Post-dispatch acceptance verification for harness actions.
 * <p>
 * {@code AgentActionDispatcher} reports whether a packet was <em>sent through the real
 * handlers</em> — but the handlers (faithfully) refuse invalid actions silently, exactly as
 * they would for a real client. A testing agent needs to know whether the server actually
 * <em>accepted</em> an action, so after dispatching we compare cheap game state snapshots
 * taken before and after:
 * <ul>
 *     <li>{@code PickupItem} — the drop left the map</li>
 *     <li>{@code Attack}/{@code SkillAttack}/{@code SkillAttackMany} — target HP dropped or the mob died</li>
 *     <li>{@code UseItem} — the item's quantity decreased</li>
 *     <li>{@code CastBuff}/{@code CastHeal} — MP decreased (skills always cost MP)</li>
 *     <li>{@code MoveTo} — the position changed (or the agent was already at the target)</li>
 *     <li>{@code MiniGame*}/{@code Room*}/{@code HiredMerchant*} — room state changed: board
 *     piece placed, first card slot / points / scores moved, room membership, merchant
 *     listings, open flag, map-box presence, or mesos moved (buys/withdrawals)</li>
 *     <li>{@code Guild*}/{@code Alliance*} — match-checker confirmations, invite-coordinator
 *     entries, guild membership/ranks/titles/notice or alliance registration changed</li>
 *     <li>{@code Engage*}/{@code BreakEngagement} — {@code marriageItemId}/{@code partnerId}
 *     set or reset (engagement registered / severed)</li>
 *     <li>{@code Family*} — a family invite became pending, or the character's family id
 *     changed (joined/absorbed)</li>
 *     <li>{@code ReactorHit} — the reactor's state advanced or it was destroyed/removed</li>
 *     <li>{@code CatchItem} — the mob was killed (caught) or the catch item was consumed</li>
 *     <li>{@code DropItem} — the stack at the inventory slot shrank</li>
 * </ul>
 * Actions that are chat/social ({@code Say}, {@code Whisper}, ...) have no cheap observable
 * effect and report {@code accepted = null} (unknown). A {@code false} always means the server
 * refused or the action could not have any effect.
 */
public final class HarnessVerifier {
    private HarnessVerifier() {
    }

    /** One action's outcome. {@code accepted} is {@code null} when acceptance is not observable. */
    public record Result(boolean dispatched, Boolean accepted) {
        public static Result of(boolean dispatched, Boolean accepted) {
            return new Result(dispatched, accepted);
        }
    }

    /** Captures the pre-dispatch snapshot needed to verify {@code action} (null = unverifiable). */
    public static Object capture(AgentSession session, AgentAction action) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return null;
        }
        if (action instanceof AgentAction.PickupItem pickup) {
            return isLiveDrop(chr, pickup.objectId());
        }
        if (action instanceof AgentAction.Attack attack) {
            return mobHp(chr, attack.targetObjectId());
        }
        if (action instanceof AgentAction.SkillAttack attack) {
            return mobHp(chr, attack.targetObjectId());
        }
        if (action instanceof AgentAction.SkillAttackMany attack) {
            long total = 0;
            for (int oid : attack.targetObjectIds()) {
                int hp = mobHp(chr, oid);
                total += hp < 0 ? 0 : hp; // absent mobs contribute 0; tracked via hp only
            }
            return total;
        }
        if (action instanceof AgentAction.UseItem use) {
            return stackQuantity(chr, use.itemId());
        }
        if (action instanceof AgentAction.CastBuff || action instanceof AgentAction.CastHeal) {
            return chr.getMp();
        }
        if (action instanceof AgentAction.MoveTo) {
            Point pos = chr.getPosition();
            return pos == null ? null : new Point(pos);
        }
        if (action instanceof AgentAction.NpcTalk || action instanceof AgentAction.NpcSelect
                || action instanceof AgentAction.NpcRespondText) {
            return new long[]{session.getNpcDialogFrameSeq(), session.isNpcDialogOpen() ? 1 : 0,
                    session.isNpcDialogDisposed() ? 1 : 0, session.isStorageOpened() ? 1 : 0};
        }
        if (action instanceof AgentAction.QuestStart quest) {
            return new long[]{questStatus(chr, quest.questId()), dialogKey(session)};
        }
        if (action instanceof AgentAction.QuestComplete quest) {
            return new long[]{questStatus(chr, quest.questId()), dialogKey(session)};
        }
        if (action instanceof AgentAction.QuestForfeit quest) {
            return questStatus(chr, quest.questId());
        }
        if (action instanceof AgentAction.AddBuddy buddy) {
            return Boolean.valueOf(hasBuddy(chr, buddy.name()));
        }
        if (action instanceof AgentAction.EnterCashShop) {
            return Boolean.valueOf(chr.getCashShop().isOpened());
        }
        if (action instanceof AgentAction.CashBuy) {
            return cashInventorySize(chr);
        }
        if (action instanceof AgentAction.TradeInvite || action instanceof AgentAction.TradeAccept
                || action instanceof AgentAction.TradePutItem || action instanceof AgentAction.TradePutMesos) {
            return tradeKey(chr);
        }
        if (action instanceof AgentAction.TradeConfirm) {
            return tradeKey(chr);
        }
        if (action instanceof AgentAction.StorageStore || action instanceof AgentAction.StorageTakeOut
                || action instanceof AgentAction.StorageMesos || action instanceof AgentAction.StorageClose) {
            return new long[]{storageKey(chr), session.isStorageOpened() ? 1 : 0};
        }
        if (action instanceof AgentAction.DueySend || action instanceof AgentAction.DueyAction) {
            return chr.getMeso(); // parcels move items/mesos; meso delta is the cheap observable
        }
        if (action instanceof AgentAction.EnterPortal) {
            return (long) chr.getMapId(); // entering a portal normally changes the map
        }
        // ---- PlayerInteraction rooms (minigames / hired merchants) ----
        if (action instanceof AgentAction.MiniGameCreate) {
            return Boolean.valueOf(chr.getMiniGame() != null);
        }
        if (action instanceof AgentAction.RoomVisit) {
            return Boolean.valueOf(inRoom(chr));
        }
        if (action instanceof AgentAction.MiniGameStart || action instanceof AgentAction.MiniGameGiveUp
                || action instanceof AgentAction.MiniGameTieAnswer
                || action instanceof AgentAction.MiniGameSelectCard) {
            MiniGame game = chr.getMiniGame();
            return game == null ? null : miniGameKey(game);
        }
        if (action instanceof AgentAction.MiniGameMoveOmok move) {
            MiniGame game = chr.getMiniGame();
            if (game == null) {
                return null;
            }
            return new int[]{game.getPieceAt(move.x(), move.y()), game.isMatchInProgress() ? 1 : 0};
        }
        if (action instanceof AgentAction.RoomExit) {
            return Boolean.valueOf(inRoom(chr));
        }
        if (action instanceof AgentAction.HiredMerchantCreate) {
            return Boolean.valueOf(chr.getHiredMerchant() != null);
        }
        if (action instanceof AgentAction.HiredMerchantPutItem || action instanceof AgentAction.HiredMerchantTakeItemBack) {
            return merchantItemsKey(chr.getHiredMerchant());
        }
        if (action instanceof AgentAction.HiredMerchantOpen) {
            HiredMerchant merchant = findOwnedMerchant(chr);
            return merchant == null ? null : Boolean.valueOf(merchant.isOpen());
        }
        if (action instanceof AgentAction.HiredMerchantBuy || action instanceof AgentAction.HiredMerchantWithdrawMesos) {
            return (long) chr.getMeso(); // both flows move mesos; the delta is the observable
        }
        if (action instanceof AgentAction.HiredMerchantClose) {
            return Boolean.valueOf(findOwnedMerchant(chr) != null);
        }
        // ---- guild / alliance / marriage / family ----
        if (action instanceof AgentAction.GuildCreate) {
            return (long) matchConfirmationLeaderId(chr); // a confirmation should appear for this leader
        }
        if (action instanceof AgentAction.GuildCreateAnswer) {
            return new long[]{matchConfirmationLeaderId(chr), chr.getGuildId()};
        }
        if (action instanceof AgentAction.GuildInvite invite) {
            return pendingGuildInvite(chr, invite.targetName());
        }
        if (action instanceof AgentAction.GuildAcceptInvite) {
            return (long) chr.getGuildId();
        }
        if (action instanceof AgentAction.GuildLeave) {
            return (long) chr.getGuildId();
        }
        if (action instanceof AgentAction.GuildExpel expel) {
            return Boolean.valueOf(guildHasMember(chr, expel.targetId()));
        }
        if (action instanceof AgentAction.GuildChangeRankTitle) {
            return guildRankTitlesKey(chr);
        }
        if (action instanceof AgentAction.GuildChangeRank change) {
            return (long) guildMemberRank(chr, change.targetId());
        }
        if (action instanceof AgentAction.GuildNotice) {
            var guild = chr.getGuild();
            return guild == null ? null : guild.getNotice();
        }
        if (action instanceof AgentAction.AllianceInvite invite) {
            return pendingAllianceInvite(chr, invite.guildName());
        }
        if (action instanceof AgentAction.AllianceAcceptInvite) {
            return (long) chr.getGuildId() <= 0 ? null : (long) chr.getGuild().getAllianceId();
        }
        if (action instanceof AgentAction.AllianceLeave) {
            return (long) (chr.getGuild() == null ? 0 : chr.getGuild().getAllianceId());
        }
        if (action instanceof AgentAction.Engage) {
            return (long) chr.getMarriageItemId();
        }
        if (action instanceof AgentAction.EngageAnswer answer) {
            Character proposer = net.server.agent.AgentActionDispatcher.findPlayerByName(chr, answer.proposerName());
            return new long[]{chr.getPartnerId(), proposer == null ? -1 : proposer.getMarriageItemId()};
        }
        if (action instanceof AgentAction.BreakEngagement) {
            return (long) chr.getPartnerId();
        }
        if (action instanceof AgentAction.FamilyAdd add) {
            return pendingFamilyInvite(chr, add.name());
        }
        if (action instanceof AgentAction.FamilyAccept) {
            return (long) (chr.getFamily() == null ? -1 : chr.getFamily().getID());
        }
        // ---- world interaction (reactors / catch items / drops) ----
        if (action instanceof AgentAction.ReactorHit hit) {
            return reactorKey(chr, hit.reactorObjectId());
        }
        if (action instanceof AgentAction.CatchItem catchItem) {
            return new long[]{chr.getMap().getMonsterByOid(catchItem.mobObjectId()) == null ? 0 : 1,
                    inventoryQuantity(chr, InventoryType.USE, catchItem.itemId())};
        }
        if (action instanceof AgentAction.DropItem drop) {
            Item slotItem = slotItem(chr, drop.inventoryType(), drop.slot());
            return slotItem == null ? null : new long[]{slotItem.getItemId(), slotItem.getQuantity()};
        }
        return null; // chat/social and bookkeeping actions: acceptance not observable
    }

    /**
     * Compares post-dispatch state against the captured snapshot.
     *
     * @return true (accepted), false (refused / no possible effect), or null (not observable)
     */
    public static Boolean verify(AgentSession session, AgentAction action, Object pre) {
        if (pre == null) {
            return null;
        }
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return false;
        }
        if (action instanceof AgentAction.PickupItem pickup) {
            // accepted iff the drop is no longer live on the map (picked up or removed)
            return pre.equals(Boolean.TRUE) && !isLiveDrop(chr, pickup.objectId());
        }
        if (action instanceof AgentAction.Attack || action instanceof AgentAction.SkillAttack) {
            int before = (Integer) pre;
            int after = action instanceof AgentAction.Attack a
                    ? mobHp(chr, a.targetObjectId())
                    : mobHp(chr, ((AgentAction.SkillAttack) action).targetObjectId());
            return hpDropped(before, after);
        }
        if (action instanceof AgentAction.SkillAttackMany) {
            long before = (Long) pre;
            long after = 0;
            for (int oid : ((AgentAction.SkillAttackMany) action).targetObjectIds()) {
                int hp = mobHp(chr, oid);
                after += hp < 0 ? 0 : hp;
            }
            return after < before;
        }
        if (action instanceof AgentAction.UseItem use) {
            return (Integer) verifyStack(chr, use.itemId(), (Integer) pre) == 1;
        }
        if (action instanceof AgentAction.CastBuff || action instanceof AgentAction.CastHeal) {
            return chr.getMp() < (Integer) pre;
        }
        if (action instanceof AgentAction.MoveTo move) {
            Point post = chr.getPosition();
            Point before = (Point) pre;
            if (post == null) {
                return false;
            }
            if (!post.equals(before)) {
                return true; // the agent moved
            }
            // Already at the target: goal achieved even though nothing moved.
            // Tolerance mirrors AgentMovement.MAX_STEP (150 px) but stays dependency-light.
            double dx = post.x - move.x();
            double dy = post.y - move.y();
            return dx * dx + dy * dy <= 150.0 * 150.0;
        }
        if (action instanceof AgentAction.NpcTalk) {
            long[] before = (long[]) pre;
            // A fresh dialog frame must have arrived (handles re-opened identical dialogs);
            // storage NPCs open the storage UI instead of a dialog - that counts as accepted too.
            return session.getNpcDialogFrameSeq() != before[0]
                    || (session.isStorageOpened() && before[3] == 0);
        }
        if (action instanceof AgentAction.NpcSelect || action instanceof AgentAction.NpcRespondText) {
            long[] before = (long[]) pre;
            if (before[0] == 0) {
                return false; // no dialog frame was ever seen
            }
            // Accepted iff a new frame arrived, or the script disposed in response (the final
            // response of a script disposes without sending a new frame).
            return session.getNpcDialogFrameSeq() != before[0]
                    || (session.isNpcDialogDisposed() && before[2] == 0);
        }
        if (action instanceof AgentAction.QuestStart || action instanceof AgentAction.QuestComplete) {
            // Accepted iff the quest script opened (dialog appeared/changed) or the quest state
            // actually transitioned. The status flip alone would miss scripted starts, which
            // only force the state several dialog frames in.
            long[] before = (long[]) pre;
            return before[0] != questStatus(chr, questIdOf(action)) || before[1] != dialogKey(session);
        }
        if (action instanceof AgentAction.QuestForfeit quest) {
            return questStatus(chr, quest.questId()) == 0; // back to NOT_STARTED
        }
        if (action instanceof AgentAction.AddBuddy buddy) {
            return !((Boolean) pre) && hasBuddy(chr, buddy.name());
        }
        if (action instanceof AgentAction.EnterCashShop) {
            return !((Boolean) pre) && chr.getCashShop().isOpened();
        }
        if (action instanceof AgentAction.CashBuy) {
            return cashInventorySize(chr) > (Integer) pre; // a cash item entered the CS inventory
        }
        if (action instanceof AgentAction.TradeInvite || action instanceof AgentAction.TradeAccept) {
            // Accepted iff a trade session now exists with a partner
            var trade = chr.getTrade();
            return trade != null && trade.getPartner() != null && !pre.equals(tradeKey(chr));
        }
        if (action instanceof AgentAction.TradePutItem || action instanceof AgentAction.TradePutMesos
                || action instanceof AgentAction.TradeConfirm) {
            return !pre.equals(tradeKey(chr)); // trade contents changed (items/mesos/stage)
        }
        if (action instanceof AgentAction.StorageStore || action instanceof AgentAction.StorageTakeOut
                || action instanceof AgentAction.StorageMesos || action instanceof AgentAction.StorageClose) {
            long[] before = (long[]) pre;
            return before[0] != storageKey(chr) || before[1] != (session.isStorageOpened() ? 1 : 0);
        }
        if (action instanceof AgentAction.DueySend || action instanceof AgentAction.DueyAction) {
            return !pre.equals((long) chr.getMeso()); // mesos moved (parcel fee/payment)
        }
        if (action instanceof AgentAction.EnterPortal) {
            return !pre.equals((long) chr.getMapId()); // the map changed (or the portal was blocked)
        }
        // ---- PlayerInteraction rooms (minigames / hired merchants) ----
        if (action instanceof AgentAction.MiniGameCreate) {
            return !((Boolean) pre) && chr.getMiniGame() != null;
        }
        if (action instanceof AgentAction.RoomVisit) {
            return !((Boolean) pre) && inRoom(chr);
        }
        if (action instanceof AgentAction.MiniGameStart || action instanceof AgentAction.MiniGameGiveUp
                || action instanceof AgentAction.MiniGameTieAnswer) {
            MiniGame game = chr.getMiniGame();
            return game != null && !pre.equals(miniGameKey(game));
        }
        if (action instanceof AgentAction.MiniGameMoveOmok move) {
            MiniGame game = chr.getMiniGame();
            if (game == null) {
                return false;
            }
            int[] before = (int[]) pre;
            if (before[0] != 0) {
                return false; // square already occupied — the server refuses
            }
            // A winning move clears the board (match ends), so accept either the placed
            // piece or the match having finished.
            return game.getPieceAt(move.x(), move.y()) == move.piece() || !game.isMatchInProgress();
        }
        if (action instanceof AgentAction.MiniGameSelectCard select) {
            MiniGame game = chr.getMiniGame();
            if (game == null) {
                return false;
            }
            if (!pre.equals(miniGameKey(game))) {
                return true; // first slot recorded, the flip scored, or the match ended
            }
            // A non-scoring second flip only broadcasts — not observable (like chat).
            return select.turn() == 0 ? null : false;
        }
        if (action instanceof AgentAction.RoomExit) {
            return ((Boolean) pre) && !inRoom(chr);
        }
        if (action instanceof AgentAction.HiredMerchantCreate) {
            HiredMerchant merchant = chr.getHiredMerchant();
            return !((Boolean) pre) && merchant != null && merchant.isOwner(chr);
        }
        if (action instanceof AgentAction.HiredMerchantPutItem || action instanceof AgentAction.HiredMerchantTakeItemBack) {
            return !pre.equals(merchantItemsKey(chr.getHiredMerchant()));
        }
        if (action instanceof AgentAction.HiredMerchantOpen) {
            HiredMerchant merchant = findOwnedMerchant(chr);
            if (merchant == null || !merchant.isOpen()) {
                return false;
            }
            return pre == null || !((Boolean) pre); // merchant box appeared / opened on the map
        }
        if (action instanceof AgentAction.HiredMerchantBuy || action instanceof AgentAction.HiredMerchantWithdrawMesos) {
            return !pre.equals((long) chr.getMeso());
        }
        if (action instanceof AgentAction.HiredMerchantClose) {
            return ((Boolean) pre) && findOwnedMerchant(chr) == null;
        }
        // ---- guild / alliance / marriage / family ----
        if (action instanceof AgentAction.GuildCreate) {
            return !pre.equals((long) matchConfirmationLeaderId(chr)); // a confirmation was created
        }
        if (action instanceof AgentAction.GuildCreateAnswer answer) {
            long[] before = (long[]) pre;
            long leaderId = matchConfirmationLeaderId(chr);
            if (before[0] == -1) {
                return false; // no pending confirmation was ever created for this character
            }
            if (answer.accept()) {
                if (chr.getGuildId() > 0) {
                    return true; // the guild was founded (all co-founders confirmed)
                }
                // accepted but other co-founders have not answered yet: not observable yet
                return leaderId != -1 ? null : false;
            }
            return leaderId == -1 && chr.getGuildId() == 0; // confirmation dismissed without joining
        }
        if (action instanceof AgentAction.GuildInvite invite) {
            Boolean before = (Boolean) pre;
            if (before == null) {
                return null; // target was never resolvable — nothing to compare
            }
            return !before && Boolean.TRUE.equals(pendingGuildInvite(chr, invite.targetName()));
        }
        if (action instanceof AgentAction.GuildAcceptInvite) {
            return !pre.equals(0L) ? false : chr.getGuildId() > 0; // joined a guild (0 -> gid)
        }
        if (action instanceof AgentAction.GuildLeave) {
            return !pre.equals(0L) && chr.getGuildId() == 0; // back to guild-less
        }
        if (action instanceof AgentAction.GuildExpel expel) {
            return ((Boolean) pre) && !guildHasMember(chr, expel.targetId());
        }
        if (action instanceof AgentAction.GuildChangeRankTitle) {
            return !pre.equals(guildRankTitlesKey(chr)); // rank titles changed
        }
        if (action instanceof AgentAction.GuildChangeRank change) {
            int before = (int) (long) (Long) pre;
            if (before == -1) {
                return false; // target was never a guild member
            }
            int after = guildMemberRank(chr, change.targetId());
            return after != -1 && after != before;
        }
        if (action instanceof AgentAction.GuildNotice) {
            var guild = chr.getGuild();
            return guild != null && !pre.equals(guild.getNotice());
        }
        if (action instanceof AgentAction.AllianceInvite invite) {
            Boolean before = (Boolean) pre;
            if (before == null) {
                return null; // target guild was never resolvable
            }
            return !before && Boolean.TRUE.equals(pendingAllianceInvite(chr, invite.guildName()));
        }
        if (action instanceof AgentAction.AllianceAcceptInvite) {
            return pre == null ? null : (!pre.equals(0L) ? false : chr.getGuild() != null && chr.getGuild().getAllianceId() > 0);
        }
        if (action instanceof AgentAction.AllianceLeave) {
            return !pre.equals(0L) && (chr.getGuild() == null || chr.getGuild().getAllianceId() == 0);
        }
        if (action instanceof AgentAction.Engage) {
            return !pre.equals(0L) ? false : chr.getMarriageItemId() > 0; // proposal registered (item reserved)
        }
        if (action instanceof AgentAction.EngageAnswer answer) {
            long[] before = (long[]) pre;
            if (answer.accept()) {
                if (before[0] > 0) {
                    return false; // already engaged — the handler refuses
                }
                return chr.getPartnerId() > 0; // engaged (both partnerId + ETC box set)
            }
            Character proposer = net.server.agent.AgentActionDispatcher.findPlayerByName(chr, answer.proposerName());
            return proposer != null && before[1] > 0 && proposer.getMarriageItemId() == -1; // proposal withdrawn
        }
        if (action instanceof AgentAction.BreakEngagement) {
            return !pre.equals(0L) && chr.getPartnerId() <= 0; // relationship severed
        }
        if (action instanceof AgentAction.FamilyAdd add) {
            Boolean before = (Boolean) pre;
            if (before == null) {
                return null; // target was never resolvable — nothing to compare
            }
            return !before && Boolean.TRUE.equals(pendingFamilyInvite(chr, add.name()));
        }
        if (action instanceof AgentAction.FamilyAccept answer) {
            if (!answer.accept()) {
                return null; // a denial only notifies the inviter — not observable here
            }
            long before = (Long) pre;
            long after = chr.getFamily() == null ? -1 : chr.getFamily().getID();
            return after != -1 && after != before; // joined a family (or absorbed one)
        }
        // ---- world interaction (reactors / catch items / drops) ----
        if (action instanceof AgentAction.ReactorHit hit) {
            long[] before = (long[]) pre;
            if (before[0] == 0) {
                return false; // reactor was never on the map
            }
            long[] after = reactorKey(chr, hit.reactorObjectId());
            if (after[0] == 0) {
                return true; // the reactor object left the map entirely (destroyed)
            }
            return after[1] != before[1] || after[2] != before[2]; // state advanced / destroyed
        }
        if (action instanceof AgentAction.CatchItem catchItem) {
            long[] before = (long[]) pre;
            if (before[0] == 0) {
                return false; // mob was never there
            }
            // A successful catch kills the mob (and consumes the item); anything else leaves both intact.
            return chr.getMap().getMonsterByOid(catchItem.mobObjectId()) == null
                    || inventoryQuantity(chr, InventoryType.USE, catchItem.itemId()) < before[1];
        }
        if (action instanceof AgentAction.DropItem drop) {
            long[] before = (long[]) pre;
            Item slotItem = slotItem(chr, drop.inventoryType(), drop.slot());
            long nowQty = slotItem == null ? 0 : slotItem.getQuantity();
            return nowQty < before[1]; // the stack at the slot shrank (dropped)
        }
        return null;
    }

    /** Whether the character is inside any PlayerInteraction room (minigame or hired merchant). */
    private static boolean inRoom(Character chr) {
        return chr.getMiniGame() != null || chr.getHiredMerchant() != null;
    }

    // ------------------------------------------------------------------
    // guild / alliance / marriage / family snapshots
    // ------------------------------------------------------------------

    /** The match-checker confirmation leader for this character, or -1 when none is pending. */
    private static int matchConfirmationLeaderId(Character chr) {
        return chr.getWorldServer().getMatchCheckerCoordinator().getMatchConfirmationLeaderid(chr.getId());
    }

    /** Whether {@code targetName} has a pending guild invite from anyone (null: unresolvable). */
    private static Boolean pendingGuildInvite(Character chr, String targetName) {
        Character target = AgentActionDispatcher.findPlayerByName(chr, targetName);
        return target == null ? null
                : Boolean.valueOf(InviteCoordinator.hasInvite(InviteType.GUILD, target.getId()));
    }

    /**
     * Whether the master of guild {@code guildName} has a pending alliance invite (null:
     * guild unknown or its master offline — the handler would not have created an invite).
     */
    private static Boolean pendingAllianceInvite(Character chr, String guildName) {
        Guild guild = Server.getInstance().getGuildByName(guildName);
        if (guild == null) {
            return null;
        }
        GuildCharacter master = guild.getMGC(guild.getLeaderId());
        if (master == null || master.getCharacter() == null) {
            return null;
        }
        return Boolean.valueOf(InviteCoordinator.hasInvite(InviteType.ALLIANCE, master.getId()));
    }

    /** Whether {@code targetName} has a pending family invite from anyone (null: unresolvable). */
    private static Boolean pendingFamilyInvite(Character chr, String targetName) {
        Character target = AgentActionDispatcher.findPlayerByName(chr, targetName);
        return target == null ? null
                : Boolean.valueOf(InviteCoordinator.hasInvite(InviteType.FAMILY, target.getId()));
    }

    private static boolean guildHasMember(Character chr, int cid) {
        Guild guild = chr.getGuild();
        if (guild == null) {
            return false;
        }
        for (GuildCharacter mgc : guild.getMembers()) {
            if (mgc.getId() == cid) {
                return true;
            }
        }
        return false;
    }

    /** Guild rank of member {@code cid}, or -1 when the character is guild-less / cid absent. */
    private static int guildMemberRank(Character chr, int cid) {
        Guild guild = chr.getGuild();
        if (guild == null) {
            return -1;
        }
        for (GuildCharacter mgc : guild.getMembers()) {
            if (mgc.getId() == cid) {
                return mgc.getGuildRank();
            }
        }
        return -1;
    }

    /** Hash of the five guild rank titles (0 when guild-less). */
    private static long guildRankTitlesKey(Character chr) {
        Guild guild = chr.getGuild();
        if (guild == null) {
            return 0;
        }
        long key = 17;
        for (int rank = 1; rank <= 5; rank++) {
            String title = guild.getRankTitle(rank);
            key = key * 31 + (title == null ? 0 : title.hashCode());
        }
        return key;
    }

    /** Minigame snapshot key: type, piece, match state, scores/points, first card slot, visitor. */
    private static long miniGameKey(MiniGame game) {
        long key = game.getGameType().getValue();
        key = key * 31 + game.getPieceType();
        key = key * 31 + (game.isMatchInProgress() ? 1 : 0);
        key = key * 31 + game.getOwnerScore();
        key = key * 31 + game.getVisitorScore();
        key = key * 31 + game.getOwnerPoints();
        key = key * 31 + game.getVisitorPoints();
        key = key * 31 + game.getFirstSlot();
        Character visitor = game.getVisitor();
        return key * 31 + (visitor == null ? 0 : visitor.getId());
    }

    /** Hired-merchant listing hash: itemId/bundle per slot (0 when there is no merchant). */
    private static long merchantItemsKey(HiredMerchant merchant) {
        if (merchant == null) {
            return 0;
        }
        long key = 17;
        for (PlayerShopItem psi : merchant.getItems()) {
            key = key * 31 + psi.getItem().getItemId() * 1000L + psi.getBundles();
        }
        return key;
    }

    /**
     * The hired merchant owned by {@code chr}: either the maintenance-mode reference, or the
     * public merchant box on the map (the owner's reference is released once the store opens).
     */
    private static HiredMerchant findOwnedMerchant(Character chr) {
        HiredMerchant owned = chr.getHiredMerchant();
        if (owned != null && owned.isOwner(chr)) {
            return owned;
        }
        if (chr.getMap() == null) {
            return null;
        }
        for (MapObject ob : chr.getMap().getMapObjects()) {
            if (ob instanceof HiredMerchant merchant && merchant.getOwnerId() == chr.getId()) {
                return merchant;
            }
        }
        return null;
    }

    /** Storage snapshot key: hash of slots, mesos and stored items. */
    private static long storageKey(Character chr) {
        var storage = chr.getStorage();
        if (storage == null) {
            return 0;
        }
        long key = storage.getSlots() * 31L + storage.getMeso();
        for (var item : storage.getItems()) {
            key = key * 31 + item.getItemId() * 1000L + item.getQuantity();
        }
        return key;
    }

    private static boolean hasBuddy(Character chr, String name) {
        return chr.getBuddylist().get(name) != null;
    }

    private static int cashInventorySize(Character chr) {
        return chr.getCashShop().getInventory().size();
    }

    /** Trade snapshot key: 0 = no trade, else hash of partner + staged contents + stage. */
    private static long tradeKey(Character chr) {
        var trade = chr.getTrade();
        if (trade == null) {
            return 0;
        }
        long key = trade.getPartner() != null ? trade.getPartner().getChr().getId() * 31L : 7L;
        key = key * 31 + trade.getMeso();
        key = key * 31 + (trade.isFullTrade() ? 1 : 0);
        for (var item : trade.getItems()) {
            key = key * 31 + item.getItemId() * 1000L + item.getQuantity();
        }
        return key;
    }

    private static int questIdOf(AgentAction action) {
        return action instanceof AgentAction.QuestStart q ? q.questId() : ((AgentAction.QuestComplete) action).questId();
    }

    private static long dialogKey(AgentSession session) {
        String text = session.getNpcDialogText();
        return session.getNpcDialogMsgType() * 31L + session.getNpcDialogNpcId() * 8191L
                + (text == null ? -1L : text.hashCode());
    }

    /** Quest status snapshot: -1 unknown, 0 not started, 1 started, 2 completed. */
    private static int questStatus(Character chr, int questId) {
        return switch (chr.getQuest(questId).getStatus()) {
            case UNDEFINED -> -1;
            case NOT_STARTED -> 0;
            case STARTED -> 1;
            case COMPLETED -> 2;
        };
    }

    private static boolean hpDropped(int before, int after) {
        if (before < 0) {
            return false; // target never existed
        }
        return after < 0 || after < before; // died or damaged
    }

    private static int mobHp(Character chr, int objectId) {
        var mob = chr.getMap().getMonsterByOid(objectId);
        return mob == null ? -1 : mob.getHp();
    }

    private static boolean isLiveDrop(Character chr, int objectId) {
        MapObject ob = chr.getMap().getMapObject(objectId);
        return ob instanceof MapItem mapItem && !mapItem.isPickedUp();
    }

    private static int stackQuantity(Character chr, int itemId) {
        return inventoryQuantity(chr, InventoryType.USE, itemId);
    }

    /** Total quantity of {@code itemId} across all stacks of the given inventory (0 when absent). */
    private static int inventoryQuantity(Character chr, InventoryType type, int itemId) {
        int total = 0;
        var inv = chr.getInventory(type);
        if (inv != null) {
            for (var item : inv.listById(itemId)) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    /** The item at an inventory slot, or null when the slot is empty / the type is unknown. */
    private static Item slotItem(Character chr, String inventoryType, short slot) {
        InventoryType type;
        try {
            type = inventoryType == null ? null
                    : InventoryType.valueOf(inventoryType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        var inv = type == null ? null : chr.getInventory(type);
        return inv == null ? null : inv.getItem(slot);
    }

    /** Reactor snapshot key: {@code [present, state, alive]} (present=0 when the oid is unknown). */
    private static long[] reactorKey(Character chr, int oid) {
        Reactor reactor = chr.getMap().getReactorByOid(oid);
        return reactor == null ? new long[]{0}
                : new long[]{1, reactor.getState(), reactor.isAlive() ? 1 : 0};
    }

    /** 1 = quantity decreased (accepted), 0 = unchanged (refused). */
    private static int verifyStack(Character chr, int itemId, int pre) {
        return stackQuantity(chr, itemId) < pre ? 1 : 0;
    }
}
