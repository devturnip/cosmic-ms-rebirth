package net.server.agent;

import client.Character;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
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

    public void dispatch(AgentSession session, AgentAction action) {
        if (action instanceof AgentAction.Idle) {
            return;
        }
        // Remember is bookkeeping only; it must never consume a packet/action budget token.
        boolean freeAction = action instanceof AgentAction.Remember;
        if (!freeAction && !session.allowAction()) {
            log.debug("Agent {} exceeded its action rate limit; dropping {}", session.getName(), action);
            return;
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
            case AgentAction.MoveTo move -> AgentMovement.moveTo(session, move.x(), move.y());
            case AgentAction.PickupItem pickup -> pickupItemFor(session, pickup.objectId());
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
            case AgentAction.Idle ignored -> {
            }
        }
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
        if (message == null) {
            return;
        }
        send(session, RecvOpcode.MULTI_CHAT, p -> {
            p.writeByte(1); // 1 = party chat
            p.writeByte(0); // no explicit recipients needed for party chat
            p.writeString(message);
        });
    }

    private void whisper(AgentSession session, String targetName, String message) {
        if (targetName == null || targetName.isBlank() || message == null) {
            return;
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
