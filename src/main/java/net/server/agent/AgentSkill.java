package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Cleric;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.server.PlayerBuffValueHolder;
import server.StatEffect;

import java.awt.Point;
import java.util.Set;

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

    /**
     * Heal skills we know how to cast safely through {@code SPECIAL_MOVE}.
     * <p>
     * Deliberately a fixed allow-list: {@code SpecialMoveHandler} only routes these to the
     * heal branch of {@link StatEffect#applyTo}, and other skills (e.g. GM Heal) expect extra
     * bytes in the packet that we do not synthesize.
     */
    private static final Set<Integer> HEAL_SKILLS = Set.of(Cleric.HEAL);

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
        if (chr.skillIsCooling(skillId)) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        StatEffect effect = skill.getEffect(chr.getSkillLevel(skill));
        if (effect == null || !effect.isSkill() || effect.getStatups().isEmpty() || effect.getDuration() <= 0) {
            return false;
        }
        if (chr.getMp() < effect.getMpCon() || chr.getHp() <= effect.getHpCon()) {
            return false;
        }
        return !isBuffActive(chr, skillId);
    }

    public static boolean isBuffActive(Character chr, int skillId) {
        for (PlayerBuffValueHolder buff : chr.getAllBuffs()) {
            if (buff != null && buff.effect != null && buff.effect.isSkill()
                    && buff.effect.getBuffSourceId() == skillId) {
                return true;
            }
        }
        return false;
    }

    public static void castBuff(AgentSession session, int skillId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || !canCastBuff(chr, skillId) || !session.isSkillReady(skillId)) {
            return;
        }
        if (session.dispatchInbound(RecvOpcode.CLOSE_RANGE_ATTACK, buildBuffPacket(skillId))) {
            session.markSkillUsed(skillId, AgentCombat.skillCooldownMs(chr, skillId));
        }
    }

    // ------------------------------------------------------------------
    // heal / support
    // ------------------------------------------------------------------

    /** Whether the skill id is a heal skill the agent has learned. */
    public static boolean isHealSkill(Character chr, int skillId) {
        return skillId > 0 && HEAL_SKILLS.contains(skillId) && chr.getSkillLevel(skillId) > 0;
    }

    /** The configured heal skill, or the first learned heal skill when none is configured. */
    static int resolveHealSkill(Character chr) {
        int configured = AgentConfig.autoHealSkill();
        if (configured > 0 && HEAL_SKILLS.contains(configured) && chr.getSkillLevel(configured) > 0) {
            return configured;
        }
        for (int candidate : HEAL_SKILLS) {
            if (chr.getSkillLevel(candidate) > 0) {
                return candidate;
            }
        }
        return 0;
    }

    /** Whether the agent can cast the given heal skill right now. */
    public static boolean canCastHeal(Character chr, int skillId) {
        if (!isHealSkill(chr, skillId) || chr.skillIsCooling(skillId)) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        return effect != null && chr.getMp() >= effect.getMpCon() && chr.getHp() > effect.getHpCon();
    }

    /** Casts a heal skill, restoring the agent and any nearby party members in its box. */
    public static boolean castHeal(AgentSession session, int skillId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return false;
        }
        if (skillId <= 0) {
            skillId = resolveHealSkill(chr); // LLM may omit/zero the id to mean "auto"
        }
        if (!canCastHeal(chr, skillId) || !session.isSkillReady(skillId)) {
            return false;
        }
        if (session.dispatchInbound(RecvOpcode.SPECIAL_MOVE, buildHealPacket(chr, skillId))) {
            session.markSkillUsed(skillId, AgentCombat.skillCooldownMs(chr, skillId));
            return true;
        }
        return false;
    }

    /**
     * Auto-casts a heal skill when the agent or a same-map party member is below the
     * configured HP percentage. One heal per call.
     */
    public static boolean autoHeal(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return false;
        }
        int skillId = resolveHealSkill(chr);
        if (skillId <= 0 || !canCastHeal(chr, skillId) || !needsHeal(chr)) {
            return false;
        }
        return castHeal(session, skillId);
    }

    /**
     * Whether the agent or an in-range party member is below the heal threshold. Party members
     * must be on the same map and within heal range, otherwise a cast would burn MP without
     * reaching anyone (the heal only applies inside the skill's bounding box).
     */
    static boolean needsHeal(Character chr) {
        int threshold = AgentConfig.healPercent();
        if (percent(chr.getHp(), chr.getCurrentMaxHp()) < threshold) {
            return true;
        }
        Point self = chr.getPosition();
        if (self == null) {
            return false;
        }
        double range = AgentConfig.attackRangeSq();
        for (Character member : chr.getPartyMembersOnline()) {
            if (member.getId() == chr.getId() || member.getMapId() != chr.getMapId()) {
                continue;
            }
            Point memberPos = member.getPosition();
            if (memberPos == null || self.distanceSq(memberPos) > range) {
                continue;
            }
            if (percent(member.getHp(), member.getCurrentMaxHp()) < threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a {@code SPECIAL_MOVE} packet for a heal skill. {@code SpecialMoveHandler} reads an
     * int, the skill id and a byte skill level, then applies the effect via
     * {@link StatEffect#applyTo(Character, java.awt.Point)}.
     * <p>
     * No trailing position is sent ({@code available() != 5}), so the heal is centred on the
     * caster; that is exactly what the handler needs for a party heal.
     */
    static InPacket buildHealPacket(Character chr, int skillId) {
        OutPacket out = new ByteBufOutPacket();
        out.writeInt(0); // unknown prefix (SpecialMoveHandler p.readInt())
        out.writeInt(skillId);
        out.writeByte(chr.getSkillLevel(skillId));
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    private static int percent(int value, int max) {
        return max <= 0 ? 100 : (int) ((long) value * 100 / max);
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
        out.skip(8);        // parseDamage's fixed 8-byte skip (charge skills would add 4 here)
        out.writeByte(0);   // display
        out.writeByte(0);   // direction
        out.writeByte(STANCE);
        out.writeByte(0);   // (non-ranged unused)
        out.writeByte(SPEED);
        out.skip(4);        // unknown
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }
}
