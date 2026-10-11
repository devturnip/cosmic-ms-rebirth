package net.server.agent;

import client.Character;
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
        if (!session.allowAction()) {
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
            StatEffect effect = ii.getItemEffect(item.getItemId());
            if (effect == null) {
                continue;
            }
            boolean hasHp = effect.getHp() > 0;
            boolean hasMp = effect.getMp() > 0;
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
        chr.respawn(chr.getMap().getReturnMapId());
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
        if (!session.allowAction()) {
            return false;
        }
        attack(session, target.getObjectId());
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
        if (chr.getPosition().distanceSq(monster.getPosition()) > MAX_ATTACK_DISTANCE_SQ) {
            return; // too far; the handler would flag a distance hack
        }
        session.dispatchInbound(RecvOpcode.CLOSE_RANGE_ATTACK,
                buildCloseRangeAttackPacket(chr, targetObjectId, safeDamage(chr)));
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
     * Builds a basic (skill 0) close-range attack packet with one target and one damage line.
     * Layout verified against {@code AbstractDealDamageHandler#parseDamage}.
     */
    static InPacket buildCloseRangeAttackPacket(Character chr, int targetObjectId, int damage) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(0);                       // unknown prefix byte
        out.writeByte(0x10 | (DAMAGE_LINES & 0xF)); // numAttackedAndDamage: 1 target, N damage lines
        out.writeInt(0);                        // skill id (0 = basic attack)
        out.skip(8);                            // charge / unknown
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
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    private static int percent(int value, int max) {
        return max <= 0 ? 100 : (int) ((long) value * 100 / max);
    }
}
