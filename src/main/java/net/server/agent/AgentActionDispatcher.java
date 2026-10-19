package net.server.agent;

import client.Character;
import client.inventory.InventoryType;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.NPC;
import server.maps.HiredMerchant;
import server.maps.MapItem;
import server.maps.MiniGame;
import server.maps.MapObject;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.function.Consumer;

/**
 * Executes {@link AgentAction}s by synthesizing the same inbound packets a real client
 * would send and dispatching them through the normal {@code PacketProcessor} handlers.
 * This keeps agent behaviour subject to the server's existing validation.
 */
public class AgentActionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AgentActionDispatcher.class);
    private static final int MAX_CHAT_LENGTH = Byte.MAX_VALUE;

    private static final List<String> BLOCKED_WORDS = List.of("fuck", "shit", "bitch", "asshole");

    /**
     * Executes an action, subject to mute and rate limits.
     *
     * @return whether the action was actually executed
     */
    public boolean dispatch(AgentSession session, AgentAction action) {
        if (action instanceof AgentAction.Idle) {
            return false;
        }
        if (session.isMuted() && (action instanceof AgentAction.Say
                || action instanceof AgentAction.PartySay
                || action instanceof AgentAction.Whisper)) {
            log.debug("Agent {} is muted; dropping {}", session.getName(), action);
            return false;
        }
        // Remember is bookkeeping only; it must never consume a packet/action budget token.
        boolean freeAction = action instanceof AgentAction.Remember;
        if (!freeAction && !session.allowAction()) {
            log.debug("Agent {} exceeded its action rate limit; dropping {}", session.getName(), action);
            return false;
        }
        logAction(session, action);
        switch (action) {
            case AgentAction.Say say -> generalChat(session, sanitize(say.message()));
            case AgentAction.PartySay say -> partyChat(session, sanitize(say.message()));
            case AgentAction.Whisper whisper -> whisper(session, whisper.targetName(), sanitize(whisper.message()));
            case AgentAction.InviteToParty invite -> inviteToParty(session, invite.targetName());
            case AgentAction.AcceptPartyInvite accept -> acceptPartyInvite(session, accept.partyId());
            case AgentAction.DenyPartyInvite deny -> denyPartyInvite(session, deny.inviterName());
            case AgentAction.LeaveParty ignored -> leaveParty(session);
            case AgentAction.MoveTo move -> AgentMovement.moveToSmart(session, new Point(move.x(), move.y()),
                    AgentMovement.MAX_STEP);
            case AgentAction.Follow follow -> follow(session, follow.targetName());
            case AgentAction.StopFollowing ignored -> session.clearFollow();
            case AgentAction.PickupItem pickup -> {
                // Ephemeral companions never loot: their inventory is destroyed with them, so the
                // drop (and any party value) would be lost. Harness sessions are ephemeral too, but
                // they exist precisely to test the drop→pickup→inventory flow, so they may loot.
                if (!session.isEphemeral() || session.isHarness()) {
                    pickupItemFor(session, pickup.objectId());
                }
            }
            case AgentAction.UseItem use -> AgentCombat.useItem(session, use.itemId());
            case AgentAction.Respawn ignored -> AgentCombat.respawn(session);
            case AgentAction.Attack attack -> AgentCombat.attack(session, attack.targetObjectId());
            case AgentAction.SkillAttack attack -> AgentCombat.attackOrFallback(session, attack.targetObjectId(), attack.skillId());
            case AgentAction.SkillAttackMany attack -> AgentCombat.attackMany(session, attack.targetObjectIds(), attack.skillId());
            // Explicit LLM decision: always allowed (pays mesos through the shop). The passive
            // per-tick behaviour is gated separately by AgentConfig.autoRecharge().
            case AgentAction.RechargeAmmo ignored -> AgentCombat.rechargeAmmo(session);
            case AgentAction.CastBuff buff -> AgentSkill.castBuff(session, buff.skillId());
            case AgentAction.CastHeal heal -> AgentSkill.castHeal(session, heal.skillId());
            case AgentAction.Remember remember -> session.getMemory().rememberFact(remember.fact());
            case AgentAction.NpcTalk talk -> npcTalk(session, talk.npcId());
            case AgentAction.NpcSelect select -> npcRespond(session, select.selection(), null);
            case AgentAction.NpcRespondText respond -> npcRespond(session, null, respond.text());
            case AgentAction.NpcEndDialog ignored -> npcEndDialog(session);
            case AgentAction.QuestStart start -> questAction(session, start.questId(), start.npcId(), false);
            case AgentAction.QuestComplete complete -> questAction(session, complete.questId(), complete.npcId(), true);
            case AgentAction.QuestForfeit forfeit -> send(session, RecvOpcode.QUEST_ACTION, p -> {
                p.writeByte(3); // forfeit
                p.writeShort(forfeit.questId());
            });
            case AgentAction.AddBuddy buddy -> addBuddy(session, buddy.name());
            case AgentAction.EnterCashShop ignored -> send(session, RecvOpcode.ENTER_CASHSHOP, p -> {
            });
            case AgentAction.CashBuy buy -> send(session, RecvOpcode.CASHSHOP_OPERATION, p -> {
                p.writeByte(0x03); // buy item
                p.writeByte(0);
                p.writeInt(buy.useNX());
                p.writeInt(buy.snCS());
            });
            case AgentAction.TradeInvite invite -> tradeInvite(session, invite.targetName());
            case AgentAction.TradeAccept ignored -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(4)); // VISIT
            case AgentAction.TradePutItem put -> {
                InventoryType type = parseInventoryType(put.inventoryType());
                if (type == null) {
                    log.debug("Agent {} trade put: unknown inventory type '{}'", session.getName(), put.inventoryType());
                } else {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0xF); // SET_ITEMS
                        p.writeByte(type.getType());
                        p.writeShort(put.slot());
                        p.writeShort(put.quantity());
                        p.writeByte(put.targetSlot());
                    });
                }
            }
            case AgentAction.TradePutMesos mesos -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                p.writeByte(0x10); // SET_MESO
                p.writeInt(mesos.amount());
            });
            case AgentAction.TradeConfirm ignored -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x11));
            case AgentAction.StorageStore store -> send(session, RecvOpcode.STORAGE, p -> {
                p.writeByte(5); // store
                p.writeShort(store.slot());
                p.writeInt(store.itemId());
                p.writeShort(store.quantity());
            });
            case AgentAction.StorageTakeOut takeOut -> {
                InventoryType type = parseInventoryType(takeOut.inventoryType());
                if (type == null) {
                    log.debug("Agent {} storage take-out: unknown inventory type '{}'", session.getName(), takeOut.inventoryType());
                } else {
                    send(session, RecvOpcode.STORAGE, p -> {
                        p.writeByte(4); // take out
                        p.writeByte(type.getType());
                        p.writeByte(takeOut.slot());
                    });
                }
            }
            case AgentAction.StorageMesos mesos -> send(session, RecvOpcode.STORAGE, p -> {
                p.writeByte(7); // mesos
                p.writeInt(mesos.amount());
            });
            case AgentAction.StorageClose ignored -> {
                send(session, RecvOpcode.STORAGE, p -> p.writeByte(8));
                session.setStorageOpened(false); // the server sends no close frame; model the UI flow
            }
            case AgentAction.DueySend send_ -> send(session, RecvOpcode.DUEY_ACTION, p -> {
                p.writeByte(0x02); // TOSERVER_SEND_ITEM
                InventoryType type = parseInventoryType(send_.inventoryType());
                p.writeByte(type == null ? 0 : type.getType());
                p.writeShort(send_.slot());
                p.writeShort(send_.quantity());
                p.writeInt(send_.mesos());
                p.writeString(send_.recipient() == null ? "" : send_.recipient());
                boolean quick = send_.message() == null;
                p.writeByte(quick ? 1 : 0);
                if (!quick) {
                    p.writeString(send_.message());
                }
            });
            case AgentAction.DueyAction duey -> send(session, RecvOpcode.DUEY_ACTION, p -> {
                p.writeByte((byte) duey.operation());
                if (duey.operation() == 0x04 || duey.operation() == 0x05) {
                    p.writeInt(duey.packageId());
                }
            });
            case AgentAction.EnterPortal portal -> {
                if (portal.portalName() == null || portal.portalName().isBlank()) {
                    log.debug("Agent {} portal enter: blank portal name", session.getName());
                } else {
                    // CHANGE_MAP_SPECIAL: the real client's packet for scripted portals.
                    send(session, RecvOpcode.CHANGE_MAP_SPECIAL, p -> {
                        p.writeByte(0);
                        p.writeString(portal.portalName());
                        p.writeShort(0);
                    });
                }
            }
            case AgentAction.MiniGameCreate create -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                p.writeByte(0); // CREATE
                boolean matchCard = create.gameType() != null && create.gameType().strip().equalsIgnoreCase("matchcard");
                p.writeByte(matchCard ? 2 : 1); // 1 = omok, 2 = match card
                p.writeString(create.description() == null ? "" : create.description());
                if (create.password() == null || create.password().isEmpty()) {
                    p.writeByte(0); // no password
                } else {
                    p.writeByte(1);
                    p.writeString(create.password());
                }
                p.writeByte(create.pieceType());
            });
            case AgentAction.RoomVisit visit -> roomVisit(session, visit.ownerName(), visit.password());
            case AgentAction.MiniGameStart ignored -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x3D)); // START
                }
            }
            case AgentAction.MiniGameMoveOmok move -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0x40); // MOVE_OMOK
                        p.writeInt(move.x());
                        p.writeInt(move.y());
                        p.writeByte(move.piece());
                    });
                }
            }
            case AgentAction.MiniGameSelectCard select -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0x44); // SELECT_CARD
                        p.writeByte(select.turn());
                        p.writeByte(select.slot());
                    });
                }
            }
            case AgentAction.MiniGameGiveUp ignored -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x34)); // GIVE_UP
                }
            }
            case AgentAction.MiniGameTieRequest ignored -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x32)); // REQUEST_TIE
                }
            }
            case AgentAction.MiniGameTieAnswer answer -> {
                if (hasMiniGame(session)) {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0x33); // ANSWER_TIE
                        p.writeByte(answer.accept() ? 1 : 0);
                    });
                }
            }
            case AgentAction.RoomExit ignored ->
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x0A)); // EXIT
            case AgentAction.HiredMerchantCreate create -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                p.writeByte(0); // CREATE
                p.writeByte(5); // hired merchant shop room
                p.writeString(create.description() == null ? "" : create.description());
                p.writeByte(0); // no password
                p.writeShort(0); // (room-type padding the handler skips)
                p.writeInt(create.itemId());
            });
            case AgentAction.HiredMerchantPutItem put -> {
                InventoryType type = parseInventoryType(put.inventoryType());
                if (type == null) {
                    log.debug("Agent {} merchant put-item: unknown inventory type '{}'", session.getName(), put.inventoryType());
                } else {
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0x21); // PUT_ITEM
                        p.writeByte(type.getType());
                        p.writeShort(put.slot());
                        p.writeShort(put.bundles());
                        p.writeShort(put.perBundle());
                        p.writeInt(put.price());
                    });
                }
            }
            case AgentAction.HiredMerchantOpen ignored ->
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                        p.writeByte(0x0B); // OPEN_STORE
                        p.writeByte(1);
                    });
            case AgentAction.HiredMerchantBuy buy -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                p.writeByte(0x22); // MERCHANT_BUY
                p.writeByte(buy.itemIndex());
                p.writeShort(buy.quantity());
            });
            case AgentAction.HiredMerchantWithdrawMesos ignored ->
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x2B)); // MERCHANT_MESO
            case AgentAction.HiredMerchantTakeItemBack take -> send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
                p.writeByte(0x26); // TAKE_ITEM_BACK
                p.writeShort(take.slot());
            });
            case AgentAction.HiredMerchantClose ignored ->
                    send(session, RecvOpcode.PLAYER_INTERACTION, p -> p.writeByte(0x29)); // CLOSE_MERCHANT
            // ---- guild / alliance / marriage / family (real handlers, full validation) ----
            case AgentAction.GuildCreate create -> {
                if (create.name() == null || create.name().isBlank()) {
                    log.debug("Agent {} guild create: blank name", session.getName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x02); // request guild creation (starts a match-checker confirmation)
                        p.writeString(create.name());
                    });
                }
            }
            case AgentAction.GuildCreateAnswer answer ->
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x1E); // answer guild-creation confirmation
                        p.writeInt(0);     // (unused field the handler skips)
                        p.writeByte(answer.accept() ? 1 : 0);
                    });
            case AgentAction.GuildInvite invite -> {
                if (invite.targetName() == null || invite.targetName().isBlank()) {
                    log.debug("Agent {} guild invite: blank target", session.getName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x05); // invite
                        p.writeString(invite.targetName());
                    });
                }
            }
            case AgentAction.GuildAcceptInvite accept -> {
                Character inviter = findPlayerByName(session.getPlayer(), accept.inviterName());
                int gid = inviter == null ? 0 : inviter.getGuildId();
                if (gid <= 0) {
                    // the real client learns the guild id from the invite packet; we resolve it
                    // from the inviter instead — without it the packet could never be formed.
                    log.debug("Agent {} guild accept: inviter '{}' unknown or guild-less",
                            session.getName(), accept.inviterName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x06); // accept invite
                        p.writeInt(gid);
                        p.writeInt(session.getCharacterId());
                    });
                }
            }
            case AgentAction.GuildLeave leave -> {
                if (leave.name() == null || leave.name().isBlank()) {
                    log.debug("Agent {} guild leave: blank name", session.getName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x07); // leave
                        p.writeInt(session.getCharacterId());
                        p.writeString(leave.name());
                    });
                }
            }
            case AgentAction.GuildExpel expel ->
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x08); // expel
                        p.writeInt(expel.targetId());
                        p.writeString(expel.targetName() == null ? "" : expel.targetName());
                    });
            case AgentAction.GuildChangeRankTitle change -> {
                if (change.ranks() == null || change.ranks().size() != 5) {
                    log.debug("Agent {} rank titles: exactly 5 strings required", session.getName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x0D); // rank titles
                        for (String rank : change.ranks()) {
                            p.writeString(rank == null ? "" : rank);
                        }
                    });
                }
            }
            case AgentAction.GuildChangeRank change ->
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x0E); // change member rank
                        p.writeInt(change.targetId());
                        p.writeByte(change.newRank());
                    });
            case AgentAction.GuildNotice notice -> {
                if (notice.notice() == null) {
                    log.debug("Agent {} guild notice: null notice", session.getName());
                } else {
                    send(session, RecvOpcode.GUILD_OPERATION, p -> {
                        p.writeByte(0x10); // notice
                        p.writeString(notice.notice());
                    });
                }
            }
            case AgentAction.AllianceInvite invite -> {
                if (invite.guildName() == null || invite.guildName().isBlank()) {
                    log.debug("Agent {} alliance invite: blank guild name", session.getName());
                } else {
                    send(session, RecvOpcode.ALLIANCE_OPERATION, p -> {
                        p.writeByte(0x03); // invite guild
                        p.writeString(invite.guildName());
                    });
                }
            }
            case AgentAction.AllianceAcceptInvite accept -> {
                Character inviter = findPlayerByName(session.getPlayer(), accept.inviterName());
                int allianceId = inviter == null || inviter.getGuild() == null ? 0 : inviter.getGuild().getAllianceId();
                if (allianceId <= 0) {
                    log.debug("Agent {} alliance accept: inviter '{}' has no alliance",
                            session.getName(), accept.inviterName());
                } else {
                    send(session, RecvOpcode.ALLIANCE_OPERATION, p -> {
                        p.writeByte(0x04); // accept invite
                        p.writeInt(allianceId);
                    });
                }
            }
            case AgentAction.AllianceLeave ignored ->
                    send(session, RecvOpcode.ALLIANCE_OPERATION, p -> p.writeByte(0x02)); // leave alliance
            case AgentAction.Engage engage -> {
                if (engage.targetName() == null || engage.targetName().isBlank()) {
                    log.debug("Agent {} engage: blank target", session.getName());
                } else {
                    send(session, RecvOpcode.RING_ACTION, p -> {
                        p.writeByte(0); // send proposal
                        p.writeString(engage.targetName());
                        p.writeInt(engage.itemId());
                    });
                }
            }
            case AgentAction.EngageAnswer answer -> {
                // The real client echoes the proposer's name + id from the request packet;
                // resolve the id the same way the server addressed the proposal.
                Character proposer = findPlayerByName(session.getPlayer(), answer.proposerName());
                if (proposer == null) {
                    log.debug("Agent {} engage answer: proposer '{}' unknown", session.getName(), answer.proposerName());
                } else {
                    send(session, RecvOpcode.RING_ACTION, p -> {
                        p.writeByte(2); // accept/deny proposal
                        p.writeByte(answer.accept() ? 1 : 0);
                        p.writeString(answer.proposerName());
                        p.writeInt(proposer.getId());
                    });
                }
            }
            case AgentAction.BreakEngagement breakUp ->
                    send(session, RecvOpcode.RING_ACTION, p -> {
                        p.writeByte(3); // break engagement / divorce (drops the token/ring)
                        p.writeInt(breakUp.itemId());
                    });
            case AgentAction.FamilyAdd add -> {
                if (add.name() == null || add.name().isBlank()) {
                    log.debug("Agent {} family add: blank name", session.getName());
                } else {
                    send(session, RecvOpcode.ADD_FAMILY, p -> p.writeString(add.name()));
                }
            }
            case AgentAction.FamilyAccept answer -> {
                Character inviter = findPlayerByName(session.getPlayer(), answer.inviterName());
                if (inviter == null) {
                    log.debug("Agent {} family answer: inviter '{}' unknown", session.getName(), answer.inviterName());
                } else {
                    send(session, RecvOpcode.ACCEPT_FAMILY, p -> {
                        p.writeInt(inviter.getId());
                        p.writeString(""); // (inviter name echo, skipped by the handler)
                        p.writeByte(answer.accept() ? 1 : 0);
                    });
                }
            }
            // ---- world interaction: reactors / catch items / item drops ----
            case AgentAction.ReactorHit hit -> {
                Character chr = session.getPlayer();
                if (chr == null || chr.getMap() == null
                        || chr.getMap().getReactorByOid(hit.reactorObjectId()) == null) {
                    log.debug("Agent {} reactor hit: reactor {} not on map",
                            session.getName(), hit.reactorObjectId());
                } else {
                    // DAMAGE_REACTOR: oid, charPos, stance, 4 skipped bytes, skill id.
                    // Stance 1 avoids the type-2-reactor refusal on stance 0/2 (ReactorHitHandler).
                    send(session, RecvOpcode.DAMAGE_REACTOR, p -> {
                        p.writeInt(hit.reactorObjectId());
                        p.writeInt(0);      // charPos
                        p.writeShort(1);    // stance
                        p.writeInt(0);      // skipped block
                        p.writeInt(0);      // skill id (plain hit)
                    });
                }
            }
            case AgentAction.CatchItem catchItem -> {
                Character chr = session.getPlayer();
                if (chr == null || chr.getMap() == null
                        || chr.getMap().getMonsterByOid(catchItem.mobObjectId()) == null) {
                    log.debug("Agent {} catch item: mob {} not on map",
                            session.getName(), catchItem.mobObjectId());
                } else {
                    // USE_CATCH_ITEM: timestamp + short block (skipped), then itemId, mob oid.
                    send(session, RecvOpcode.USE_CATCH_ITEM, p -> {
                        p.writeInt(0);
                        p.writeShort(0);
                        p.writeInt(catchItem.itemId());
                        p.writeInt(catchItem.mobObjectId());
                    });
                }
            }
            case AgentAction.DropItem drop -> {
                InventoryType type = parseInventoryType(drop.inventoryType());
                if (type == null) {
                    log.debug("Agent {} drop item: unknown inventory type '{}'",
                            session.getName(), drop.inventoryType());
                } else {
                    // ITEM_MOVE with action 0 = drop (see ItemMoveHandler → InventoryManipulator.drop).
                    send(session, RecvOpcode.ITEM_MOVE, p -> {
                        p.writeInt(0); // timestamp block the handler skips
                        p.writeByte(type.getType());
                        p.writeShort(drop.slot());
                        p.writeShort(0); // action 0 = drop
                        p.writeShort(drop.quantity());
                    });
                }
            }
            case AgentAction.Idle ignored -> {
            }
        }
        return true;
    }

    private void logAction(AgentSession session, AgentAction action) {
        // Chat/party actions are audit-logged at INFO; high-frequency movement stays at DEBUG.
        if (action instanceof AgentAction.Say || action instanceof AgentAction.PartySay || action instanceof AgentAction.Whisper) {
            log.info("[agent {}] {}", session.getName(), action);
        } else {
            log.debug("[agent {}] {}", session.getName(), action);
        }
    }

    private void generalChat(AgentSession session, String message) {
        if (message == null) {
            return;
        }
        send(session, RecvOpcode.GENERAL_CHAT, p -> {
            p.writeString(message);
            p.writeByte(0);
        });
    }

    private void partyChat(AgentSession session, String message) {
        if (message == null || partyHasOptedOutMember(session)) {
            return;
        }
        send(session, RecvOpcode.MULTI_CHAT, p -> {
            p.writeByte(1); // 1 = party chat
            p.writeByte(0); // no explicit recipients needed for party chat
            p.writeString(message);
        });
    }

    /** Whether any other member of the agent's party has opted out of agent interaction. */
    private static boolean partyHasOptedOutMember(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getParty() == null) {
            return false;
        }
        for (PartyCharacter member : chr.getParty().getMembers()) {
            if (member.getId() != chr.getId() && AgentManager.getInstance().isOptedOut(member.getId())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the named player, when resolvable, has opted out of AI-agent interaction. */
    private static boolean isOptedOutTarget(AgentSession session, String targetName) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return false;
        }
        Character target = findPlayerByName(chr, targetName);
        return target != null && AgentManager.getInstance().isOptedOut(target.getId());
    }

    /** Resolves a nearby or same-world player by name and sets it as the agent's manual follow. */
    private void follow(AgentSession session, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return;
        }
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        Character target = findPlayerByName(chr, targetName);
        if (target != null && !AgentManager.getInstance().isOptedOut(target.getId())) {
            session.setManualFollow(target.getId());
        }
    }

    /** Resolves a nearby or same-world player by name (also used by the harness verifier). */
    public static Character findPlayerByName(Character chr, String name) {
        if (chr == null || name == null || chr.getMap() == null) {
            return null;
        }
        for (Character player : chr.getMap().getAllPlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        World world = chr.getWorldServer();
        if (world != null) {
            for (Character player : world.getPlayerStorage().getAllCharacters()) {
                if (player.getName().equalsIgnoreCase(name)) {
                    return player;
                }
            }
        }
        return null;
    }

    private void whisper(AgentSession session, String targetName, String message) {
        if (targetName == null || targetName.isBlank() || message == null) {
            return;
        }
        if (isOptedOutTarget(session, targetName)) {
            return; // honour the player's opt-out
        }
        send(session, RecvOpcode.WHISPER, p -> {
            p.writeByte(PacketCreator.WhisperFlag.WHISPER | PacketCreator.WhisperFlag.REQUEST);
            p.writeString(targetName);
            p.writeString(message);
        });
    }

    private void inviteToParty(AgentSession session, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return;
        }
        if (isOptedOutTarget(session, targetName)) {
            return; // honour the player's opt-out
        }
        send(session, RecvOpcode.PARTY_OPERATION, p -> {
            p.writeByte(4); // invite
            p.writeString(targetName);
        });
    }

    private void acceptPartyInvite(AgentSession session, int partyId) {
        send(session, RecvOpcode.PARTY_OPERATION, p -> {
            p.writeByte(3); // join
            p.writeInt(partyId);
        });
    }

    private void denyPartyInvite(AgentSession session, String inviterName) {
        if (inviterName == null || inviterName.isBlank()) {
            return;
        }
        send(session, RecvOpcode.DENY_PARTY_REQUEST, p -> {
            p.writeByte(0);
            p.writeString("PS: " + inviterName);
        });
    }

    private void leaveParty(AgentSession session) {
        send(session, RecvOpcode.PARTY_OPERATION, p -> p.writeByte(2)); // leave
    }

    /**
     * Validates and dispatches an item-pickup for {@code objectId}. Static so server-driven
     * behaviours (auto-loot) can reuse the exact same range/ownership checks without going
     * through the rate-limited action dispatch path twice.
     */
    static void pickupItemFor(AgentSession session, int objectId) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        MapObject ob = chr.getMap().getMapObject(objectId);
        if (!(ob instanceof MapItem mapItem)) {
            return;
        }
        Point pos = chr.getPosition();
        Point obPos = ob.getPosition();
        if (pos == null || obPos == null) {
            return;
        }
        // Mirror the range ItemPickupHandler enforces, then the same ownership rule the server
        // applies in Character#pickupItem (owner/party/FFA/expired).
        if (Math.abs(pos.x - obPos.x) > 800 || Math.abs(pos.y - obPos.y) > 600) {
            return;
        }
        if (!AgentCombat.canLoot(chr, mapItem)) {
            return;
        }
        send(session, RecvOpcode.ITEM_PICKUP, p -> {
            p.writeInt(0); // timestamp
            p.writeByte(0);
            p.writePos(pos);
            p.writeInt(objectId);
        });
    }

    // ------------------------------------------------------------------
    // npc dialogs
    // ------------------------------------------------------------------

    /** Opens a dialog with the NPC {@code npcId} (must be present on the agent's map). */
    private void npcTalk(AgentSession session, int npcId) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        Integer oid = findNpcOid(chr, npcId);
        if (oid == null) {
            log.debug("Agent {} cannot talk to npc {}: not on map {}", session.getName(), npcId, chr.getMapId());
            return;
        }
        send(session, RecvOpcode.NPC_TALK, p -> p.writeInt(oid));
    }

    /**
     * Responds to the pending dialog. The response encoding must mirror the real client per
     * frame type:
     * <ul>
     *     <li>{@code yes_no} (1) and {@code accept_decline} (5) — the answer rides the
     *     <em>action</em> byte (1 = yes/accept, 0 = no/decline), no selection bytes.</li>
     *     <li>{@code selection} (4) and {@code number_input} (3) — action 1 + int selection.</li>
     *     <li>{@code say} (0) — action 1 ("next"), no extra bytes.</li>
     *     <li>{@code text_input} (2) — action 1 + string (only via {@code NpcRespondText}).</li>
     * </ul>
     * Mismatched responses are refused (mirroring a real client, which cannot send them).
     */
    private void npcRespond(AgentSession session, Integer selection, String text) {
        int msgType = session.getNpcDialogMsgType();
        if (msgType < 0 || !isValidResponse(msgType, selection, text)) {
            log.debug("Agent {} cannot respond to dialog type {} (selection={}, text={})",
                    session.getName(), msgType, selection, text);
            return;
        }
        send(session, RecvOpcode.NPC_TALK_MORE, p -> {
            p.writeByte(msgType);
            switch (msgType) {
                case 2 -> {
                    p.writeByte(1);
                    p.writeString(text);
                }
                case 1, 5, 12 -> p.writeByte(selection == 1 ? (byte) 1 : (byte) 0); // answer in the action byte
                case 3, 4 -> {
                    p.writeByte(1);
                    p.writeInt(selection);
                }
                default -> p.writeByte(1); // say and other single-response frames: "next"
            }
        });
    }

    private static boolean isValidResponse(int msgType, Integer selection, String text) {
        return switch (msgType) {
            case 2 -> text != null && selection == null;
            case 1, 5, 12, 3, 4 -> text == null && selection != null;
            default -> text == null; // say frames take a plain continue
        };
    }

    /**
     * Fires a quest start/complete via the client's real QUEST_ACTION packets, auto-picking
     * the variant the quest supports: scripted quests (JS in scripts/quest) use actions 4/5,
     * which run the script (its dialog frames flow through the normal dialog actions);
     * WZ-only quests use actions 1/2, which apply the WZ-driven checks/rewards directly.
     * Actions 1/2 and 4/5 all carry full server validation — nothing is bypassed.
     */
    private void questAction(AgentSession session, int questId, int npcId, boolean end) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        if (findNpcOid(chr, npcId) == null) {
            log.debug("Agent {} cannot {} quest {}: npc {} is not on map {}", session.getName(),
                    end ? "complete" : "start", questId, npcId, chr.getMapId());
            return;
        }
        boolean scripted = server.quest.Quest.getInstance(questId).hasScriptRequirement(end);
        byte action = (byte) (scripted ? (end ? 5 : 4) : (end ? 2 : 1));
        send(session, RecvOpcode.QUEST_ACTION, p -> {
            p.writeByte(action);
            p.writeShort(questId);
            p.writeInt(npcId);
        });
    }

    /** Ends the pending dialog (Esc / End Chat). Always sendable while any frame is known —
     * this is the escape hatch when the server-side context is stuck open. */
    private static void npcEndDialog(AgentSession session) {
        int msgType = session.getNpcDialogMsgType();
        if (msgType < 0) {
            return;
        }
        send(session, RecvOpcode.NPC_TALK_MORE, p -> {
            p.writeByte(msgType);
            p.writeByte(0); // 0 = end chat
        });
        session.setNpcDialogClosed();
    }

    private static InventoryType parseInventoryType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return InventoryType.valueOf(name.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // social (buddy / trade)
    // ------------------------------------------------------------------

    /** Adds a buddy by name (mode 1 of the buddylist handler). */
    private void addBuddy(AgentSession session, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        send(session, RecvOpcode.BUDDYLIST_MODIFY, p -> {
            p.writeByte(1); // add
            p.writeString(name);
            p.writeString("Friends");
        });
    }

    /** Invites a player on the agent's map to a trade. */
    private void tradeInvite(AgentSession session, String targetName) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Character target = null;
        for (Character player : chr.getMap().getAllPlayers()) {
            if (player.getName().equalsIgnoreCase(targetName)) {
                target = player;
                break;
            }
        }
        final Character invitee = target;
        if (invitee == null) {
            log.debug("Agent {} cannot trade with '{}': not on map {}", session.getName(), targetName, chr.getMapId());
            return;
        }
        send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
            p.writeByte(2); // INVITE
            p.writeInt(invitee.getId());
        });
    }

    // ------------------------------------------------------------------
    // player-interaction rooms (minigames / hired merchants)
    // ------------------------------------------------------------------

    private static boolean hasMiniGame(AgentSession session) {
        Character chr = session.getPlayer();
        return chr != null && chr.getMiniGame() != null;
    }

    /**
     * Visits the room (minigame, player shop or hired merchant) owned by {@code ownerName} on
     * the agent's map, mirroring the client's room-click packet (VISIT carries the room's
     * map object id; minigame rooms additionally carry an optional password).
     */
    private void roomVisit(AgentSession session, String ownerName, String password) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getMap() == null || ownerName == null || ownerName.isBlank()) {
            log.debug("Agent {} room visit: no map or blank owner name", session.getName());
            return;
        }
        MapObject room = null;
        for (MapObject ob : chr.getMap().getMapObjects()) {
            if (ob instanceof MiniGame game && game.getOwner() != null
                    && game.getOwner().getName() != null
                    && game.getOwner().getName().equalsIgnoreCase(ownerName)) {
                room = ob;
                break;
            }
            if (ob instanceof HiredMerchant merchant && merchant.getOwner() != null
                    && merchant.getOwner().equalsIgnoreCase(ownerName)) {
                room = ob;
                break;
            }
        }
        if (room == null) {
            log.debug("Agent {} cannot visit room of '{}': no such room on map {}", session.getName(),
                    ownerName, chr.getMapId());
            return;
        }
        final MapObject target = room;
        boolean minigame = target instanceof MiniGame;
        send(session, RecvOpcode.PLAYER_INTERACTION, p -> {
            p.writeByte(4); // VISIT
            p.writeInt(target.getObjectId());
            if (minigame) {
                p.writeByte(0); // room-type marker the handler skips
                if (password != null && !password.isEmpty()) {
                    p.writeString(password);
                }
            }
        });
    }

    private static Integer findNpcOid(Character chr, int npcId) {
        for (MapObject obj : chr.getMap().getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                java.util.List.of(server.maps.MapObjectType.NPC))) {
            if (obj instanceof NPC npc && npc.getId() == npcId) {
                return obj.getObjectId();
            }
        }
        return null;
    }

    private static void send(AgentSession session, RecvOpcode opcode, Consumer<OutPacket> writer) {
        OutPacket out = new ByteBufOutPacket();
        writer.accept(out);
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
        session.dispatchInbound(opcode, in);
    }

    /**
     * Trims, clamps and filters a message before it is sent. Returns {@code null} when the
     * message is empty or filtered out, in which case the action is skipped.
     */
    String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String message = raw.strip();
        if (message.isEmpty()) {
            return null;
        }
        while (!message.isEmpty() && isCommandHeading(message.charAt(0))) {
            message = message.substring(1).strip();
        }
        if (message.isEmpty()) {
            return null;
        }
        if (AgentConfig.contentFilter()) {
            message = applyContentFilter(message);
        }
        if (message.length() > MAX_CHAT_LENGTH) {
            message = message.substring(0, MAX_CHAT_LENGTH);
        }
        return message;
    }

    private static boolean isCommandHeading(char c) {
        // '/', '@' and '!' are all interpreted as command headings by CommandsExecutor/GeneralChatHandler.
        return c == '/' || c == '@' || c == '!';
    }

    private String applyContentFilter(String message) {
        String filtered = message;
        for (String word : BLOCKED_WORDS) {
            int idx;
            while ((idx = filtered.toLowerCase().indexOf(word)) >= 0) {
                filtered = filtered.substring(0, idx) + "*".repeat(word.length()) + filtered.substring(idx + word.length());
            }
        }
        return filtered;
    }
}
