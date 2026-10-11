package net.server.agent;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
            5101004, 15101003 // Corkscrew Blow: special charge skill
    );

    /** Ranged skills whose parseDamage branch reads an extra 4-byte block. */
    private static final Set<Integer> RANGED_EXTRA_SKILLS = Set.of(
            3121004,  // Hurricane
            3221001,  // Piercing Arrow
            5221004,  // Rapid Fire
            13111002  // KoC Hurricane
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

    /** Attacks the nearest living monster(s) within range, using the configured skill when usable. */
    public static boolean attackNearest(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null) {
            return false;
        }
        int skillId = AgentConfig.attackSkill();
        if (skillId > 0 && canUseAttackSkill(chr, skillId)) {
            int maxTargets = AgentConfig.aoeAttack()
                    ? Math.min(AgentConfig.aoeMaxTargets(), mobCount(chr, skillId))
                    : 1;
            List<Integer> targets = nearestMonsters(chr, maxTargets);
            if (targets.isEmpty()) {
                return false;
            }
            if (targets.size() == 1) {
                skillAttack(session, targets.get(0), skillId);
            } else {
                skillAttackMany(session, targets, skillId);
            }
            return true;
        }
        List<Integer> targets = nearestMonsters(chr, 1);
        if (targets.isEmpty()) {
            return false;
        }
        attack(session, targets.get(0));
        return true;
    }

    /** Living monsters within attack range, nearest first, capped at {@code max}. */
    static List<Integer> nearestMonsters(Character chr, int max) {
        List<Integer> result = new ArrayList<>();
        Point pos = chr.getPosition();
        if (pos == null || chr.getMap() == null || max <= 0) {
            return result;
        }
        double range = Math.min(AgentConfig.attackRangeSq(), MAX_ATTACK_DISTANCE_SQ);
        List<Monster> candidates = new ArrayList<>();
        for (MapObject object : chr.getMap().getMapObjectsInRange(pos, range, Arrays.asList(MapObjectType.MONSTER))) {
            if (object instanceof Monster monster && monster.getHp() > 0 && monster.getPosition() != null
                    && pos.distanceSq(monster.getPosition()) <= range) {
                candidates.add(monster);
            }
        }
        candidates.sort(Comparator.comparingDouble(m -> pos.distanceSq(m.getPosition())));
        for (Monster monster : candidates) {
            if (result.size() >= max) {
                break;
            }
            result.add(monster.getObjectId());
        }
        return result;
    }

    /** Monster targets a skill can hit in one cast, mirroring the server's raw mob count. */
    static int mobCount(Character chr, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        return effect == null ? 0 : effect.getMobCount();
    }

    /**
     * Picks up the nearest drop the agent owns (or a free drop) within the pickup handler's
     * range box. One item per call.
     */
    public static boolean lootNearest(AgentSession session) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null) {
            return false;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return false;
        }
        int bestOid = -1;
        double bestDistance = Double.MAX_VALUE;
        for (MapObject object : chr.getMap().getMapObjectsInRange(pos, 1_000_000.0, Arrays.asList(MapObjectType.ITEM))) {
            if (!(object instanceof MapItem mapItem)) {
                continue;
            }
            Point itemPos = mapItem.getPosition();
            if (itemPos == null) {
                continue;
            }
            // Mirror ItemPickupHandler's range box, then use the same ownership rule the
            // server enforces in Character#pickupItem (owner/party/FFA/expired).
            if (Math.abs(pos.x - itemPos.x) > 800 || Math.abs(pos.y - itemPos.y) > 600) {
                continue;
            }
            if (!canLoot(chr, mapItem)) {
                continue;
            }
            double distance = pos.distanceSq(itemPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestOid = mapItem.getObjectId();
            }
        }
        if (bestOid < 0) {
            return false;
        }
        AgentActionDispatcher.pickupItemFor(session, bestOid);
        return true;
    }

    /**
     * Non-mutating equivalent of {@code MapItem#canBePickedBy}: the agent may take drops it owns,
     * its party's drops, FFA/expired drops, and drops owned by a party member.
     */
    static boolean canLoot(Character chr, MapItem mapItem) {
        if (mapItem.getOwnerId() <= 0 || mapItem.isFFADrop() || mapItem.getOwnerId() == chr.getId()) {
            return true;
        }
        if (chr.getPartyId() > 0 && mapItem.getPartyOwnerId() == chr.getPartyId()) {
            return true;
        }
        return chr.isPartyMember(mapItem.getOwnerId());
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
        int damage = safeDamage(chr);
        if (isRangedWeapon(chr)) {
            if (canUseRangedAttack(chr, 1)) {
                session.dispatchInbound(RecvOpcode.RANGED_ATTACK, buildRangedAttackPacket(0, targetObjectId, damage));
            }
            // Out of ammo: never swing a bow/gun/claw as if it were a melee weapon.
            return;
        }
        // A skill-0 packet must go through CLOSE_RANGE_ATTACK: MagicDamageHandler dereferences
        // SkillFactory.getSkill(0) (null) for magic packets regardless of skill id.
        session.dispatchInbound(basicAttackOpcode(), buildAttackPacket(0, targetObjectId, damage));
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
        int damage = skillDamage(chr, skillId);
        if (!isMagicSkill(chr, skillId) && isRangedWeapon(chr)) {
            if (!canUseRangedAttack(chr, requiredAmmo(chr, skillId))) {
                return; // out of ammo; do not fall back to a melee swing with a ranged weapon
            }
            session.dispatchInbound(RecvOpcode.RANGED_ATTACK, buildRangedAttackPacket(skillId, targetObjectId, damage));
        } else {
            session.dispatchInbound(attackOpcode(chr, skillId), buildAttackPacket(skillId, targetObjectId, damage));
        }
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
     * Hits several monsters with one damaging skill packet. Targets that moved out of range or
     * died are dropped; the count is capped by both config and the skill's own mob count so the
     * server never sees a mob-count hack.
     */
    public static void skillAttackMany(AgentSession session, List<Integer> targetObjectIds, int skillId) {
        Character chr = session.getPlayer();
        if (chr == null || !chr.isAlive() || chr.getMap() == null || !canUseAttackSkill(chr, skillId)) {
            return;
        }
        int maxTargets = Math.max(1, Math.min(AgentConfig.aoeMaxTargets(), mobCount(chr, skillId)));
        double range = Math.min(AgentConfig.attackRangeSq(), MAX_ATTACK_DISTANCE_SQ);
        List<Integer> valid = new ArrayList<>();
        for (int oid : targetObjectIds) {
            if (valid.size() >= maxTargets) {
                break;
            }
            MapObject object = chr.getMap().getMapObject(oid);
            if (!(object instanceof Monster monster) || monster.getHp() <= 0 || monster.getPosition() == null) {
                continue;
            }
            if (chr.getPosition().distanceSq(monster.getPosition()) > range) {
                continue;
            }
            valid.add(oid);
        }
        if (valid.isEmpty()) {
            return;
        }
        if (valid.size() == 1) {
            skillAttack(session, valid.get(0), skillId);
            return;
        }
        int damage = skillDamage(chr, skillId);
        if (!isMagicSkill(chr, skillId) && isRangedWeapon(chr)) {
            if (!canUseRangedAttack(chr, requiredAmmo(chr, skillId))) {
                return; // out of ammo; do not fall back to a melee swing with a ranged weapon
            }
            session.dispatchInbound(RecvOpcode.RANGED_ATTACK, buildRangedAttackPacket(skillId, valid, damage));
        } else {
            session.dispatchInbound(attackOpcode(chr, skillId), buildAttackPacket(skillId, valid, damage));
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
        // to 100 and utility skills (e.g. Teleport, Shadow Web) would slip through with a zero
        // server max and rack up DAMAGE_HACK points. For physical skills, require the WZ data to
        // actually carry a damage node rather than the 100 default.
        if (effect.getMatk() > 0) {
            return true;
        }
        return effect.hasDamage() && effect.getMatk() <= 0;
    }

    /** Whether the agent can use the given skill as a damaging attack right now. */
    static boolean canUseAttackSkill(Character chr, int skillId) {
        if (!isAttackSkill(chr, skillId) || chr.skillIsCooling(skillId) || mobCount(chr, skillId) <= 0) {
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

    /** Whether the agent can use a ranged attack requiring {@code requiredAmmo} projectiles. */
    static boolean canUseRangedAttack(Character chr, int requiredAmmo) {
        WeaponType type = rangedWeaponType(chr);
        if (type == null) {
            return false;
        }
        if (chr.getBuffedValue(BuffStat.SOULARROW) != null) {
            return true; // Soul Arrow consumes no ammunition
        }
        return hasMatchingAmmo(chr, type, requiredAmmo);
    }

    static boolean canUseRangedAttack(Character chr) {
        return canUseRangedAttack(chr, 1);
    }

    static boolean isRangedWeapon(Character chr) {
        return rangedWeaponType(chr) != null;
    }

    /** The equipped weapon type when it is a ranged type, otherwise {@code null}. */
    static WeaponType rangedWeaponType(Character chr) {
        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) {
            return null;
        }
        Item weapon = equipped.getItem((short) -11);
        if (weapon == null) {
            return null;
        }
        WeaponType type = ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
        switch (type) {
            case BOW, CROSSBOW, GUN, CLAW -> {
                return type;
            }
            default -> {
                return null;
            }
        }
    }

    /** Whether the USE inventory holds enough valid projectiles for the weapon. */
    static boolean hasMatchingAmmo(Character chr, WeaponType type, int required) {
        Inventory use = chr.getInventory(InventoryType.USE);
        if (use == null || type == null) {
            return false;
        }
        int need = Math.max(1, required);
        for (Item item : use.list()) {
            if (item == null || item.getQuantity() < need) {
                continue;
            }
            if (isValidAmmo(chr, type, item.getItemId())) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors RangedAttackHandler's weapon-type and level gating for a projectile. */
    static boolean isValidAmmo(Character chr, WeaponType type, int id) {
        switch (type) {
            case BOW -> {
                return ItemConstants.isArrowForBow(id);
            }
            case CROSSBOW -> {
                return ItemConstants.isArrowForCrossBow(id);
            }
            case GUN -> {
                if (!ItemConstants.isBullet(id)) {
                    return false;
                }
                if (id == ItemId.BLAZE_CAPSULE || id == ItemId.GLAZE_CAPSULE) {
                    return chr.getLevel() >= 70;
                }
                return chr.getLevel() > (id % 10) * 20 + 9;
            }
            case CLAW -> {
                if (!ItemConstants.isThrowingStar(id)) {
                    return false;
                }
                if (id == ItemId.HWABI_THROWING_STARS || id == ItemId.BALANCED_FURY) {
                    return chr.getLevel() >= 70;
                }
                if (id == ItemId.CRYSTAL_ILBI_THROWING_STARS) {
                    return chr.getLevel() >= 50;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Projectiles the ranged handler needs for this skill (doubled by Shadow Partner). */
    static int requiredAmmo(Character chr, int skillId) {
        if (skillId <= 0) {
            return 1;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        int count = effect == null ? 1 : Math.max(1, effect.getBulletCount());
        if (chr.getBuffedValue(BuffStat.SHADOWPARTNER) != null) {
            count *= 2;
        }
        return count;
    }

    private static boolean isMagicSkill(Character chr, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill == null ? null : skill.getEffect(chr.getSkillLevel(skill));
        return effect != null && effect.getMatk() > 0;
    }

    /**
     * Builds a ranged attack packet. The ranged branch of {@code parseDamage} reads a longer
     * fixed header than the close-range branch, plus an extra block for hurricane-like skills.
     */
    static InPacket buildRangedAttackPacket(int skillId, int targetObjectId, int damage) {
        return buildRangedAttackPacket(skillId, List.of(targetObjectId), damage);
    }

    /** Ranged attack packet hitting every target in {@code targetObjectIds}. */
    static InPacket buildRangedAttackPacket(int skillId, List<Integer> targetObjectIds, int damage) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(0);                       // unknown prefix byte
        out.writeByte(((targetObjectIds.size() & 0xF) << 4) | (DAMAGE_LINES & 0xF)); // numAttackedAndDamage
        out.writeInt(skillId);
        if (CHARGE_SKILLS.contains(skillId)) {
            out.writeInt(0);
        }
        out.skip(8);
        out.writeByte(0);   // display
        out.writeByte(0);   // direction
        out.writeByte(4);   // stance
        out.writeByte(0);   // (ranged: unused)
        out.writeByte(4);   // speed
        out.writeByte(0);   // (ranged: unused)
        out.writeByte(0);   // rangedirection
        out.skip(7);
        if (RANGED_EXTRA_SKILLS.contains(skillId)) {
            out.skip(4);
        }
        for (int targetObjectId : targetObjectIds) {
            out.writeInt(targetObjectId);
            out.skip(14);
            for (int i = 0; i < DAMAGE_LINES; i++) {
                out.writeInt(damage);
            }
            out.skip(4);
        }
        if (skillId == POISON_BOMB) {
            out.skip(4);
            out.writePos(new Point(0, 0));
        }
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
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
     * Builds an attack packet with one or more targets and one damage line each. The layout is
     * identical for close-range and magic attacks (only the opcode differs), and matches
     * {@code AbstractDealDamageHandler#parseDamage}. Charge skills get an extra int, and Poison
     * Bomb a trailing position, exactly as the parser reads them.
     */
    static InPacket buildAttackPacket(int skillId, int targetObjectId, int damage) {
        return buildAttackPacket(skillId, List.of(targetObjectId), damage);
    }

    /** Attack packet hitting every target in {@code targetObjectIds} with one damage line each. */
    static InPacket buildAttackPacket(int skillId, List<Integer> targetObjectIds, int damage) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(0);                       // unknown prefix byte
        out.writeByte(((targetObjectIds.size() & 0xF) << 4) | (DAMAGE_LINES & 0xF)); // numAttackedAndDamage
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
        for (int targetObjectId : targetObjectIds) {
            out.writeInt(targetObjectId);
            out.skip(14);                       // per-target unknown block
            for (int i = 0; i < DAMAGE_LINES; i++) {
                out.writeInt(damage);
            }
            out.skip(4);                        // trailing per-target
        }
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
