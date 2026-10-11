package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.server.PlayerBuffValueHolder;
import server.StatEffect;

/**
 * Skill casting for agents. Currently supports self/party buffs: a buff is cast by sending a
 * close-range attack packet with {@code numAttackedAndDamage = 0} and the skill id, which
 * makes {@code AbstractDealDamageHandler#applyAttack} apply the skill's {@link StatEffect}
 * ({@code attackEffect.applyTo(player)}).
 * <p>
 * Only skills the agent has learned whose effect has statups and a positive duration are
 * considered; MP cost is checked first, and a skill whose buff is already active is skipped.
 */
public final class AgentSkill {
    private static final int STANCE = 4;
    private static final int SPEED = 4;

    private AgentSkill() {
    }

    /** Casts the first configured buff that is castable, or does nothing. One buff per call. */
    public static boolean autoCast(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return false;
        }
        for (int skillId : AgentConfig.autoBuffSkills()) {
            if (canCastBuff(chr, skillId)) {
                castBuff(session, skillId);
                return true;
            }
        }
        return false;
    }

    /** Whether the agent can (and needs to) cast the given buff right now. */
    public static boolean canCastBuff(Character chr, int skillId) {
        if (skillId <= 0 || chr.getSkillLevel(skillId) <= 0) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        StatEffect effect = skill.getEffect(chr.getSkillLevel(skill));
        if (effect == null || effect.getStatups().isEmpty() || effect.getDuration() <= 0) {
            return false;
        }
        if (chr.getMp() < effect.getMpCon()) {
            return false;
        }
        return !isBuffActive(chr, skillId);
    }

    public static boolean isBuffActive(Character chr, int skillId) {
        for (PlayerBuffValueHolder buff : chr.getAllBuffs()) {
            if (buff != null && buff.effect != null && buff.effect.getSourceId() == skillId) {
                return true;
            }
        }
        return false;
    }

    public static void castBuff(AgentSession session, int skillId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return;
        }
        session.dispatchInbound(RecvOpcode.CLOSE_RANGE_ATTACK, buildBuffPacket(skillId));
    }

    /**
     * Builds a buff-cast packet: the close-range attack header with zero targets and the skill
     * id. Layout verified against {@code AbstractDealDamageHandler#parseDamage}.
     */
    static InPacket buildBuffPacket(int skillId) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(0);   // unknown prefix byte
        out.writeByte(0);   // numAttackedAndDamage: 0 targets, 0 damage lines
        out.writeInt(skillId);
        out.skip(8);        // charge / unknown
        out.writeByte(0);   // display
        out.writeByte(0);   // direction
        out.writeByte(STANCE);
        out.writeByte(0);   // (non-ranged unused)
        out.writeByte(SPEED);
        out.skip(4);        // unknown
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }
}
