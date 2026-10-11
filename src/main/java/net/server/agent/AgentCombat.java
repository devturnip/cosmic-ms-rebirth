package net.server.agent;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.Point;
import java.util.Arrays;
import java.util.Set;

/**
 * Combat helpers: potion self-sustain, respawn, and a basic melee attack.
 * <p>
 * Attacks are synthesized as close-range attack packets (see
 * {@code AbstractDealDamageHandler#parseDamage}) with a single target and a single damage
 * line, using a conservative damage value derived from the same base the server validates
 * against. Attacks are only issued for monsters well within the handler's distance-alert
 * threshold, and only by agents that are alive.
 */
public final class AgentCombat {
    /** The handler alerts when squared distance to the monster exceeds 200000. Stay well under it. */
    private static final double MAX_ATTACK_DISTANCE_SQ = 150000.0;
    private static final int DAMAGE_LINES = 1;

    /** Skills for which parseDamage reads an extra charge int (and Poison Bomb a trailing position). */
    private static final int POISON_BOMB = 14111006;
    private static final Set<Integer> CHARGE_SKILLS = Set.of(
            22121000, 22151001, 2121001, 2221001, 2321001, 5201002, 5101004, 15101003, POISON_BOMB);

    /** Attack skills we cannot yet model correctly (bespoke layouts, flat caps, or ranged-only). */
    private static final Set<Integer> EXCLUDED_ATTACK_SKILLS = Set.of(
            4211006,  // Meso Explosion: bespoke packet layout
            4111004,  // Shadow Meso: flat damage cap and meso cost
            4001344,  // Lucky Seven: ranged
            14111006, // Poison Bomb: ranged
            5201002,  // Grenade: ranged
            5101004, 15101003 // Corkscrew Blow: ranged
    );
    private AgentCombat() {
    }

    // ------------------------------------------------------------------
    // self-sustain
    // ------------------------------------------------------------------

    /** Uses an HP and/or MP potion when the corresponding pool is below its threshold. */
    public static boolean sustain(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return false;
        }
        boolean acted = false;
        if (percent(chr.getHp(), chr.getCurrentMaxHp()) < AgentConfig.hpPotionPercent()) {
            acted |= usePotion(session, true);
        }
        if (percent(chr.getMp(), chr.getCurrentMaxMp()) < AgentConfig.mpPotionPercent()) {
            acted |= usePotion(session, false);
        }
        return acted;
    }

    public static boolean usePotion(AgentSession session, boolean forHp) {
        Character chr = session.getPlayer();
        if (chr == null) {
            return false;
        }
        Item potion = findPotion(chr, forHp);
        if (potion == null) {
            return false;
        }
        useItem(session, potion.getItemId());
        return true;
    }

    public static void useItem(AgentSession session, int itemId) {
        Character chr = session.getPlayer();
        if (chr == null || chr.getInventory(InventoryType.USE) == null) {
            return;
        }
        Inventory use = chr.getInventory(InventoryType.USE);
        short slot = -1;
        for (Item item : use.list()) {
            if (item != null && item.getItemId() == itemId && item.getQuantity() > 0) {
                slot = item.getPosition();
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        session.dispatchInbound(RecvOpcode.USE_ITEM, buildUseItemPacket(slot, itemId));
    }

    /** Picks a dedicated potion for the requested stat, falling back to a dual-purpose elixir. */
    static Item findPotion(Character chr, boolean forHp) {
        Inventory use = chr.getInventory(InventoryType.USE);
        if (use == null) {
            return null;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item dual = null;
        for (Item item : use.list()) {
            if (item == null || item.getQuantity() <= 0) {
                continue;
            }
            // Projectiles (0206/0207) carry no "spec" data and make the item-effect loader
            // throw; restrict to the consumable/potion range.
            if (item.getItemId() < 2_000_000 || item.getItemId() >= 2_060_000) {
                continue;
            }
            StatEffect effect;
            try {
                effect = ii.getItemEffect(item.getItemId());
            } catch (RuntimeException e) {
                continue;
            }
            if (effect == null) {
                continue;
            }
            boolean hasHp = effect.getHp() > 0 || effect.getHpRate() > 0.0;
            boolean hasMp = effect.getMp() > 0 || effect.getMpRate() > 0.0;
            if (forHp ? !hasHp : !hasMp) {
                continue;
            }
            if (hasHp && hasMp) {
                if (dual == null) {
                    dual = item;
                }
            } else {
                return item; // dedicated potion is preferred
            }
        }
        return dual;
    }

    static InPacket buildUseItemPacket(short slot, int itemId) {
        OutPacket out = new ByteBufOutPacket();
        out.writeInt(0); // timestamp
        out.writeShort(slot);
        out.writeInt(itemId);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    // ------------------------------------------------------------------
    // respawn
    // ------------------------------------------------------------------

    public static void respawn(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || chr.isAlive() || chr.getMap() == null) {
            return;
        }
        int returnMap = chr.getMap().getReturnMapId();
        if (returnMap <= 0) {
            return;
        }
        // Mirror ChangeMapHandler's revive path so event/PQ scripts get a say.
        boolean executeStandardPath = true;
        if (chr.getEventInstance() != null) {
            executeStandardPath = chr.getEventInstance().revivePlayer(chr);
        }
        if (executeStandardPath) {
            chr.respawn(returnMap);
        }
    }

    // ------------------------------------------------------------------
    // attack
    // ------------------------------------------------------------------

    /** Attacks the nearest living monster within range. */
    public static boolean attackNearest(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null) {
            return false;
        }
        Point pos = chr.getPosition();
        double range = Math.min(AgentConfig.attackRangeSq(), MAX_ATTACK_DISTANCE_SQ);
        Monster target = null;
        double bestDistance = Double.MAX_VALUE;
        for (MapObject object : chr.getMap().getMapObjectsInRange(pos, range, Arrays.asList(MapObjectType.MONSTER))) {
            if (object instanceof Monster monster && monster.getHp() > 0) {
                double distance = pos.distanceSq(monster.getPosition());
                if (distance <= range && distance < bestDistance) {
                    bestDistance = distance;
                    target = monster;
                }
            }
        }
        if (target == null) {
            return false;
        }
        int skillId = AgentConfig.attackSkill();
        if (skillId > 0 && canUseAttackSkill(chr, skillId)) {
            skillAttack(session, target.getObjectId(), skillId);
        } else {
            attack(session, target.getObjectId());
        }
        return true;
    }

    public static void attack(AgentSession session, int targetObjectId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null) {
            return;
        }
        MapObject object = chr.getMap().getMapObject(targetObjectId);
        if (!(object instanceof Monster monster) || monster.getHp() <= 0) {
            return;
        }
        if (chr.getPosition().distanceSq(monster.getPosition()) > Math.min(AgentConfig.attackRangeSq(), MAX_ATTACK_DISTANCE_SQ)) {
            return; // too far; the handler would flag a distance hack
        }
        // A skill-0 packet must go through CLOSE_RANGE_ATTACK: MagicDamageHandler dereferences
        // SkillFactory.getSkill(0) (null) for magic packets regardless of skill id.
        session.dispatchInbound(basicAttackOpcode(), buildAttackPacket(0, targetObjectId, safeDamage(chr)));
    }

    /** Attacks a monster with a learned damaging skill (melee or magic). */
    public static void skillAttack(AgentSession session, int targetObjectId, int skillId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null || !canUseAttackSkill(chr, skillId)) {
            return;
        }
        MapObject object = chr.getMap().getMapObject(targetObjectId);
        if (!(object instanceof Monster monster) || monster.getHp() <= 0) {
            return;
        }
        if (chr.getPosition().distanceSq(monster.getPosition()) > Math.min(AgentConfig.attackRangeSq(), MAX_ATTACK_DISTANCE_SQ)) {
            return;
        }
        session.dispatchInbound(attackOpcode(chr, skillId),
                buildAttackPacket(skillId, targetObjectId, skillDamage(chr, skillId)));
    }

    /** Attacks with the given skill if usable, otherwise falls back to a basic attack. */
    public static void attackOrFallback(AgentSession session, int targetObjectId, int skillId) {
        Character chr = session.getPlayer();
        if (chr != null && canUseAttackSkill(chr, skillId)) {
            skillAttack(session, targetObjectId, skillId);
        } else {
            attack(session, targetObjectId);
        }
    }

    /**
     * Whether the skill is a self-consistent damaging attack skill (learned, not a buff or
     * fixed-damage/ranged-special skill). Does not consider MP or cooldown.
     */
    static boolean isAttackSkill(Character chr, int skillId) {
        if (skillId <= 0 || chr.getSkillLevel(skillId) <= 0) {
            return false;
        }
        if (EXCLUDED_ATTACK_SKILLS.contains(skillId)) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        StatEffect effect = skill.getEffect(chr.getSkillLevel(skill));
        if (effect == null || !effect.getStatups().isEmpty() || effect.getFixDamage() > 0) {
            return false;
        }
        // Magic skills must actually deal magic damage; otherwise StatEffect's damage defaults
        // to 100 and utility skills (e.g. Teleport) would slip through with a zero server max.
        if (effect.getMatk() > 0) {
            return true;
        }
        return effect.getDamage() > 0 && effect.getMatk() <= 0;
    }

    /** Whether the agent can use the given skill as a damaging attack right now. */
    static boolean canUseAttackSkill(Character chr, int skillId) {
        if (!isAttackSkill(chr, skillId) || chr.skillIsCooling(skillId)) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        return effect != null && chr.getMp() >= effect.getMpCon();
    }

    /**
     * A conservative damage value for an attack skill. The server recomputes a maximum from the
     * same base formula (buffs can only raise it), so 80% of our computed maximum stays well
     * under the alert threshold.
     */
    static int skillDamage(Character chr, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return 1;
        }
        StatEffect effect = skill.getEffect(chr.getSkillLevel(skill));
        if (effect == null) {
            return 1;
        }
        long computed;
        if (effect.getMatk() > 0) {
            int totalMagic = chr.getTotalMagic();
            int totalInt = chr.getTotalInt();
            computed = (long) (Math.ceil((totalMagic * Math.ceil(totalMagic / 1000.0) + totalMagic) / 30.0)
                    + Math.ceil(totalInt / 200.0));
            computed *= effect.getMatk();
        } else {
            computed = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        }
        computed = computed * effect.getDamage() / 100;
        return (int) Math.max(1, computed * 4 / 5);
    }

    /** Basic (skill-0) attacks always use the close-range handler. */
    static RecvOpcode basicAttackOpcode() {
        return RecvOpcode.CLOSE_RANGE_ATTACK;
    }

    /** Magic skills use MAGIC_ATTACK; physical skills use CLOSE_RANGE_ATTACK. */
    static RecvOpcode attackOpcode(Character chr, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        return effect != null && effect.getMatk() > 0 ? RecvOpcode.MAGIC_ATTACK : RecvOpcode.CLOSE_RANGE_ATTACK;
    }

    /**
     * A damage value that the server will accept: the server recomputes a maximum from the
     * character's stats (which can only be higher once buffs are applied), so the base maximum
     * is always safe.
     */
    static int safeDamage(Character chr) {
        int max = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        return Math.max(1, max);
    }

    /**
     * Builds an attack packet with one target and one damage line. The layout is identical for
     * close-range and magic attacks (only the opcode differs), and matches
     * {@code AbstractDealDamageHandler#parseDamage}. Charge skills get an extra int, and Poison
     * Bomb a trailing position, exactly as the parser reads them.
     */
    static InPacket buildAttackPacket(int skillId, int targetObjectId, int damage) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(0);                       // unknown prefix byte
        out.writeByte(0x10 | (DAMAGE_LINES & 0xF)); // numAttackedAndDamage: 1 target, N damage lines
        out.writeInt(skillId);
        if (CHARGE_SKILLS.contains(skillId)) {
            out.writeInt(0);                    // charge (read only for charge-list skills)
        }
        out.skip(8);                            // fixed 8-byte block
        out.writeByte(0);                       // display
        out.writeByte(0);                       // direction
        out.writeByte(4);                       // stance
        out.writeByte(0);                       // (non-ranged unused)
        out.writeByte(4);                       // speed
        out.skip(4);                            // unknown
        out.writeInt(targetObjectId);
        out.skip(14);                           // per-target unknown block
        for (int i = 0; i < DAMAGE_LINES; i++) {
            out.writeInt(damage);
        }
        out.skip(4);                            // trailing per-target
        if (skillId == POISON_BOMB) {
            out.skip(4);
            out.writePos(new Point(0, 0));
        }
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    /** Kept for the basic-attack callers/tests. */
    static InPacket buildCloseRangeAttackPacket(Character chr, int targetObjectId, int damage) {
        return buildAttackPacket(0, targetObjectId, damage);
    }

    private static int percent(int value, int max) {
        return max <= 0 ? 100 : (int) ((long) value * 100 / max);
    }
}
