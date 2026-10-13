package net.server.agent;

import client.Job;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bishop;
import constants.skills.Bowmaster;
import constants.skills.Cleric;
import constants.skills.Corsair;
import constants.skills.Crusader;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.Fighter;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Hunter;
import constants.skills.Magician;
import constants.skills.NightLord;
import constants.skills.Outlaw;
import constants.skills.Pirate;
import constants.skills.Priest;
import constants.skills.Ranger;
import constants.skills.Rogue;
import constants.skills.Warrior;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, DB/WZ-free companion build template: given an {@link AgentRole} and a level it decides the
 * advanced job, the AP/stat split, HP/MP, and a SP-budgeted skill build. {@link DbAgentProvisioner}
 * turns the result into a {@link client.creator.CharacterFactoryRecipe} and (using the WZ item
 * data) upgrades the fallback weapon to the best one the build can actually wear.
 * <p>
 * The skill grants always put at least one real damaging skill first so
 * {@link AgentCombat#isAttackSkill} stays usable at every tier, including the very first job
 * advancement. The healer role learns {@code Heal} from its second job (Cleric, level 30) on.
 */
final class CompanionTemplate {

    /** Levels at which each job tier begins. */
    static final int FIRST_JOB_LEVEL = 10;
    static final int SECOND_JOB_LEVEL = 30;
    static final int THIRD_JOB_LEVEL = 70;
    static final int FOURTH_JOB_LEVEL = 120;

    /** Primary (damage) stat share of the AP pool above the 4-point base; the rest goes secondary. */
    private static final int PRIMARY_SHARE_PERCENT = 70;

    private CompanionTemplate() {
    }

    /** A skill to grant, with a level already within that skill's known max. */
    record SkillGrant(int skillId, int level) {
    }

    /**
     * A complete companion build. {@code weapon} is a role fallback; the provisioner may replace it
     * with a data-driven pick that the allocated stats can wear.
     */
    record Build(
            AgentRole role,
            Job job,
            int level,
            int str, int dex, int int_, int luk,
            int remainingAp, int remainingSp,
            int maxHp, int maxMp,
            List<SkillGrant> skills,
            int top, int bottom, int shoes, int weapon,
            int extraWeapon,
            int hpPotion, int mpPotion,
            int ammoItem, int ammoQty,
            int meso) {

        /** The total of the four base stats plus unspent AP; used by tests and callers. */
        int totalStats() {
            return str + dex + int_ + luk + remainingAp;
        }
    }

    // ------------------------------------------------------------------
    // build entry points
    // ------------------------------------------------------------------

    /** Builds the level-appropriate recipe for a known role. */
    static Build build(AgentRole role, int level) {
        if (role == null) {
            return blank(Job.BEGINNER, level);
        }
        final int safeLevel = Math.max(1, level);
        final Job job = jobFor(role, safeLevel);
        final int[] stats = allocateStats(role, safeLevel);
        final int totalSp = totalSp(safeLevel);
        final List<SkillGrant> skills = allocateSkills(skillsFor(role, tierFor(safeLevel)), totalSp);
        final int usedSp = skills.stream().mapToInt(SkillGrant::level).sum();
        final int[] hpMp = hpMpFor(role, safeLevel);

        final Gear gear = gearFor(role);
        return new Build(role, job, safeLevel,
                stats[0], stats[1], stats[2], stats[3],
                stats[4], Math.max(0, totalSp - usedSp),
                hpMp[0], hpMp[1],
                List.copyOf(skills),
                gear.top(), gear.bottom(), gear.shoes(), gear.weapon(),
                gear.extraWeapon(), gear.hpPotion(), gear.mpPotion(), gear.ammoItem(), gear.ammoQty(),
                meso(safeLevel));
    }

    /** Builds a role from a caller-supplied base job, falling back to a bare build when unknown. */
    static Build buildFor(Job baseJob, int level) {
        final AgentRole role = AgentRole.forJob(baseJob);
        if (role != null) {
            return build(role, level);
        }
        return blank(baseJob != null ? baseJob : Job.BEGINNER, level);
    }

    /** A minimal, role-less build (used only for unrecognised base jobs). */
    private static Build blank(Job job, int level) {
        final int safeLevel = Math.max(1, level);
        final int totalAp = 16 + Math.max(0, safeLevel - 1) * 5;
        final int lv = Math.max(0, safeLevel - 1);
        return new Build(null, job, safeLevel,
                4, 4, 4, 4, totalAp - 16, totalSp(safeLevel),
                50 + 10 * lv, 5 + 5 * lv, List.of(),
                0, 0, 0, 0, 0,
                2000001, 2000002, 0, 0,
                meso(safeLevel));
    }

    // ------------------------------------------------------------------
    // job progression
    // ------------------------------------------------------------------

    /** Standard v83 advancement: 1st at 10, 2nd at 30, 3rd at 70, 4th at 120. */
    static Job jobFor(AgentRole role, int level) {
        if (role == null || level < FIRST_JOB_LEVEL) {
            return Job.BEGINNER;
        }
        return switch (role) {
            case WARRIOR -> pick(level, Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO);
            case MAGICIAN -> pick(level, Job.MAGICIAN, Job.FP_WIZARD, Job.FP_MAGE, Job.FP_ARCHMAGE);
            // The healer line tops out at Bishop (there is no 4th job advancement), so the
            // level-10 healer is still a Magician and only becomes a Cleric at level 30.
            case HEALER -> pick(level, Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            case BOWMAN -> pick(level, Job.BOWMAN, Job.HUNTER, Job.RANGER, Job.BOWMASTER);
            case THIEF -> pick(level, Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case PIRATE -> pick(level, Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW, Job.CORSAIR);
        };
    }

    private static Job pick(int level, Job first, Job second, Job third, Job fourth) {
        if (level >= FOURTH_JOB_LEVEL) {
            return fourth;
        }
        if (level >= THIRD_JOB_LEVEL) {
            return third;
        }
        if (level >= SECOND_JOB_LEVEL) {
            return second;
        }
        return first;
    }

    private static int tierFor(int level) {
        if (level >= FOURTH_JOB_LEVEL) {
            return 4;
        }
        if (level >= THIRD_JOB_LEVEL) {
            return 3;
        }
        if (level >= SECOND_JOB_LEVEL) {
            return 2;
        }
        return level >= FIRST_JOB_LEVEL ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // stats / AP
    // ------------------------------------------------------------------

    private enum Stat { STR, DEX, INT, LUK }

    private static Stat primaryStat(AgentRole role) {
        return switch (role) {
            case WARRIOR -> Stat.STR;
            case BOWMAN, PIRATE -> Stat.DEX;
            case MAGICIAN, HEALER -> Stat.INT;
            case THIEF -> Stat.LUK;
        };
    }

    /**
     * Spends every AP point: the four stats start at 4 and the remaining
     * {@code 16 + (level-1)*5 - 16} points are split 70/30 between the damage stat and the weapon
     * requirement stat. {@code remainingAp} is always 0.
     */
    static int[] allocateStats(AgentRole role, int level) {
        final int totalAp = 16 + Math.max(0, level - 1) * 5;
        final int extra = Math.max(0, totalAp - 16);
        final int primaryExtra = extra * PRIMARY_SHARE_PERCENT / 100;
        final int primary = 4 + primaryExtra;
        final int secondary = 4 + (extra - primaryExtra);

        int str = 4;
        int dex = 4;
        int int_ = 4;
        int luk = 4;
        switch (primaryStat(role)) {
            case STR -> {
                str = primary;
                dex = secondary;
            }
            case DEX -> {
                dex = primary;
                str = secondary;
            }
            case INT -> {
                int_ = primary;
                luk = secondary;
            }
            case LUK -> {
                luk = primary;
                dex = secondary;
            }
        }
        return new int[]{str, dex, int_, luk, totalAp - (str + dex + int_ + luk)};
    }

    private static int totalSp(int level) {
        return level >= FIRST_JOB_LEVEL ? (level - 9) * 3 : 0;
    }

    // ------------------------------------------------------------------
    // HP / MP
    // ------------------------------------------------------------------

    private static int[] hpMpFor(AgentRole role, int level) {
        final int lv = Math.max(0, level - 1);
        return switch (role) {
            case WARRIOR -> new int[]{55 + 32 * lv, 8 + 8 * lv};
            case MAGICIAN -> new int[]{45 + 12 * lv, 12 + 30 * lv};
            case HEALER -> new int[]{45 + 14 * lv, 12 + 32 * lv};
            case BOWMAN -> new int[]{50 + 24 * lv, 10 + 14 * lv};
            case THIEF -> new int[]{50 + 24 * lv, 10 + 14 * lv};
            case PIRATE -> new int[]{50 + 28 * lv, 10 + 16 * lv};
        };
    }

    private static int meso(int level) {
        return 5000 + level * 500;
    }

    // ------------------------------------------------------------------
    // weapons
    // ------------------------------------------------------------------

    /** Weapon types the role may wield at the given level; drives the data-driven item scan. */
    static Set<WeaponType> roleWeaponTypes(AgentRole role, int level) {
        if (role == null) {
            return EnumSet.noneOf(WeaponType.class);
        }
        return switch (role) {
            // Weapon types must match the skill build granted below (sword fighter, bow hunter,
            // claw assassin, gun gunslinger), otherwise masteries/attack skills would not apply.
            case WARRIOR -> EnumSet.of(WeaponType.SWORD1H, WeaponType.SWORD2H);
            case MAGICIAN, HEALER -> EnumSet.of(WeaponType.WAND, WeaponType.STAFF);
            case BOWMAN -> EnumSet.of(WeaponType.BOW);
            // A thief is a dagger rogue until the Assassin advancement at level 30, when the
            // dagger attack skill is replaced by the claw line.
            case THIEF -> level < SECOND_JOB_LEVEL
                    ? EnumSet.of(WeaponType.DAGGER_OTHER)
                    : EnumSet.of(WeaponType.CLAW);
            case PIRATE -> EnumSet.of(WeaponType.GUN);
        };
    }

    /** Weapon mask used by WZ {@code reqJob}; 1 warrior / 2 magician / 4 archer / 8 thief / 16 pirate. */
    static int jobMask(AgentRole role) {
        if (role == null) {
            return 0;
        }
        return switch (role) {
            case WARRIOR -> 1;
            case MAGICIAN, HEALER -> 2;
            case BOWMAN -> 4;
            case THIEF -> 8;
            case PIRATE -> 16;
        };
    }

    // ------------------------------------------------------------------
    // skills
    // ------------------------------------------------------------------

    private static SkillGrant skill(int id, int level) {
        return new SkillGrant(id, level);
    }

    private static void addAll(List<SkillGrant> into, List<SkillGrant> grants) {
        into.addAll(grants);
    }

    /**
     * Budgets the desired skill list against the level's SP. The first pass grants one level to
     * each skill in priority order (so the leading attack skill and {@code Heal} always make it),
     * then the second pass tops them up.
     */
    static List<SkillGrant> allocateSkills(List<SkillGrant> desired, int totalSp) {
        final int budget = Math.max(0, totalSp);
        final List<SkillGrant> granted = new ArrayList<>();
        int remaining = budget;
        for (SkillGrant g : desired) {
            if (remaining <= 0) {
                break;
            }
            if (g.level() <= 0) {
                continue;
            }
            granted.add(new SkillGrant(g.skillId(), 1));
            remaining--;
        }
        for (int i = 0; i < granted.size() && remaining > 0; i++) {
            final SkillGrant want = desired.get(i);
            final int extra = Math.min(remaining, want.level() - 1);
            if (extra > 0) {
                granted.set(i, new SkillGrant(granted.get(i).skillId(), 1 + extra));
                remaining -= extra;
            }
        }
        return granted;
    }

    private static List<SkillGrant> skillsFor(AgentRole role, int tier) {
        final List<SkillGrant> skills = new ArrayList<>();
        if (tier <= 0) {
            return skills;
        }
        switch (role) {
            case WARRIOR -> {
                addAll(skills, List.of(
                        skill(Warrior.POWER_STRIKE, 20),
                        skill(Warrior.SLASH_BLAST, 20),
                        skill(Warrior.IMPROVED_HPREC, 10),
                        skill(Warrior.IMPROVED_MAXHP, 10),
                        skill(Warrior.ENDURE, 8),
                        skill(Warrior.IRON_BODY, 20)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(Fighter.SWORD_MASTERY, 20),
                            skill(Fighter.SWORD_BOOSTER, 20),
                            skill(Fighter.RAGE, 20),
                            skill(Fighter.POWER_GUARD, 20)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(Crusader.COMBO, 20),
                            skill(Crusader.SWORD_PANIC, 30),
                            skill(Crusader.ARMOR_CRASH, 20),
                            skill(Crusader.SHOUT, 30)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(Hero.BRANDISH, 30),
                            skill(Hero.STANCE, 30),
                            skill(Hero.ADVANCED_COMBO, 10),
                            skill(Hero.ACHILLES, 20),
                            skill(Hero.MAPLE_WARRIOR, 30)));
                }
            }
            case MAGICIAN -> {
                addAll(skills, List.of(
                        skill(Magician.ENERGY_BOLT, 20),
                        skill(Magician.MAGIC_CLAW, 20),
                        skill(Magician.MAGIC_GUARD, 20),
                        skill(Magician.IMPROVED_MAX_MP_INCREASE, 10),
                        skill(Magician.IMPROVED_MP_RECOVERY, 10)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(FPWizard.FIRE_ARROW, 30),
                            skill(FPWizard.MEDITATION, 20),
                            skill(FPWizard.TELEPORT, 20),
                            skill(FPWizard.MP_EATER, 10),
                            skill(FPWizard.SLOW, 20)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(FPMage.EXPLOSION, 30),
                            skill(FPMage.ELEMENT_AMPLIFICATION, 30),
                            skill(FPMage.POISON_MIST, 30),
                            skill(FPMage.SPELL_BOOSTER, 20)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(FPArchMage.PARALYZE, 30),
                            skill(FPArchMage.METEOR_SHOWER, 30),
                            skill(FPArchMage.INFINITY, 30),
                            skill(FPArchMage.FIRE_DEMON, 30)));
                }
            }
            case HEALER -> {
                // A level-10 healer is still a Magician (Cleric needs level 30), so the first
                // tier grants the standard magician attack/utility skills. Heal arrives with
                // the Cleric advancement at tier 2.
                addAll(skills, List.of(
                        skill(Magician.ENERGY_BOLT, 20),
                        skill(Magician.MAGIC_CLAW, 20),
                        skill(Magician.MAGIC_GUARD, 20),
                        skill(Magician.IMPROVED_MAX_MP_INCREASE, 10),
                        skill(Magician.IMPROVED_MP_RECOVERY, 10)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(Cleric.HEAL, 30),
                            skill(Cleric.HOLY_ARROW, 20),
                            skill(Cleric.BLESS, 20),
                            skill(Cleric.MP_EATER, 10),
                            skill(Cleric.TELEPORT, 20),
                            skill(Cleric.INVINCIBLE, 20)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(Priest.SHINING_RAY, 30),
                            skill(Priest.HOLY_SYMBOL, 30),
                            skill(Priest.DISPEL, 20),
                            skill(Priest.ELEMENTAL_RESISTANCE, 20)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(Bishop.ANGEL_RAY, 30),
                            skill(Bishop.GENESIS, 30),
                            skill(Bishop.BAHAMUT, 30),
                            skill(Bishop.HOLY_SHIELD, 30),
                            skill(Bishop.RESURRECTION, 10),
                            skill(Bishop.MAPLE_WARRIOR, 30),
                            skill(Bishop.INFINITY, 30)));
                }
            }
            case BOWMAN -> {
                addAll(skills, List.of(
                        skill(Archer.DOUBLE_SHOT, 20),
                        skill(Archer.ARROW_BLOW, 20),
                        skill(Archer.CRITICAL_SHOT, 20),
                        skill(Archer.FOCUS, 20),
                        skill(Archer.EYE_OF_AMAZON, 8)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(Hunter.BOW_MASTERY, 20),
                            skill(Hunter.BOW_BOOSTER, 20),
                            skill(Hunter.SOUL_ARROW, 20),
                            skill(Hunter.POWER_KNOCKBACK, 20),
                            skill(Hunter.ARROW_BOMB, 30)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(Ranger.STRAFE, 30),
                            skill(Ranger.ARROW_RAIN, 30),
                            skill(Ranger.INFERNO, 30),
                            skill(Ranger.SILVER_HAWK, 20),
                            skill(Ranger.MORTAL_BLOW, 20)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(Bowmaster.HURRICANE, 30),
                            skill(Bowmaster.SHARP_EYES, 30),
                            skill(Bowmaster.BOW_EXPERT, 30),
                            skill(Bowmaster.PHOENIX, 30),
                            skill(Bowmaster.MAPLE_WARRIOR, 30)));
                }
            }
            case THIEF -> {
                // Lucky Seven is ranged-only and excluded by AgentCombat, and Double Stab is a
                // dagger skill, so it only belongs to the dagger tier. From Assassin on, lead
                // with the claw attack skill instead.
                addAll(skills, List.of(tier == 1
                        ? skill(Rogue.DOUBLE_STAB, 20)
                        : skill(Assassin.DRAIN, 20)));
                addAll(skills, List.of(
                        skill(Rogue.NIMBLE_BODY, 20),
                        skill(Rogue.KEEN_EYES, 8),
                        skill(Rogue.DARK_SIGHT, 20)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(Assassin.CLAW_MASTERY, 20),
                            skill(Assassin.CRITICAL_THROW, 20),
                            skill(Assassin.CLAW_BOOSTER, 20),
                            skill(Assassin.HASTE, 20)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(Hermit.AVENGER, 30),
                            skill(Hermit.SHADOW_PARTNER, 30),
                            skill(Hermit.FLASH_JUMP, 20),
                            skill(Hermit.SHADOW_WEB, 20),
                            skill(Hermit.MESO_UP, 20)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(NightLord.TRIPLE_THROW, 30),
                            skill(NightLord.NINJA_STORM, 30),
                            skill(NightLord.SHADOW_STARS, 30),
                            skill(NightLord.VENOMOUS_STAR, 30),
                            skill(NightLord.MAPLE_WARRIOR, 30)));
                }
            }
            case PIRATE -> {
                addAll(skills, List.of(
                        skill(Pirate.DOUBLE_SHOT, 20),
                        skill(Pirate.FLASH_FIST, 20),
                        skill(Pirate.SOMERSAULT_KICK, 20),
                        skill(Pirate.BULLET_TIME, 20),
                        skill(Pirate.DASH, 10)));
                if (tier >= 2) {
                    addAll(skills, List.of(
                            skill(Gunslinger.GUN_MASTERY, 20),
                            skill(Gunslinger.GUN_BOOSTER, 20),
                            skill(Gunslinger.INVISIBLE_SHOT, 20),
                            skill(Gunslinger.GRENADE, 20),
                            skill(Gunslinger.RECOIL_SHOT, 20)));
                }
                if (tier >= 3) {
                    addAll(skills, List.of(
                            skill(Outlaw.BURST_FIRE, 20),
                            skill(Outlaw.FLAME_THROWER, 30),
                            skill(Outlaw.ICE_SPLITTER, 30),
                            skill(Outlaw.HOMING_BEACON, 20),
                            skill(Outlaw.GAVIOTA, 20)));
                }
                if (tier >= 4) {
                    addAll(skills, List.of(
                            skill(Corsair.RAPID_FIRE, 30),
                            skill(Corsair.BATTLESHIP_CANNON, 30),
                            skill(Corsair.BATTLESHIP_TORPEDO, 30),
                            skill(Corsair.WRATH_OF_THE_OCTOPI, 20),
                            skill(Corsair.MAPLE_WARRIOR, 30)));
                }
            }
        }
        return skills;
    }

    // ------------------------------------------------------------------
    // role gear (unchanged shapes from the old inline table)
    // ------------------------------------------------------------------

    private record Gear(int top, int bottom, int shoes, int weapon, int extraWeapon,
                        int hpPotion, int mpPotion, int ammoItem, int ammoQty) {
    }

    private static Gear gearFor(AgentRole role) {
        return switch (role) {
            case WARRIOR -> new Gear(ItemId.RED_HWARANG_SHIRT,
                    ItemId.BLACK_MARTIAL_ARTS_PANTS,
                    ItemId.MITHRIL_BATTLE_GRIEVES,
                    ItemId.GLADIUS,
                    ItemId.MITHRIL_POLE_ARM,
                    ItemId.WHITE_POTION, ItemId.BLUE_POTION, 0, 0);
            case MAGICIAN, HEALER -> new Gear(ItemId.PURPLE_FAIRY_TOP,
                    0,
                    ItemId.RED_MAGICSHOES,
                    ItemId.MITHRIL_WAND,
                    0,
                    ItemId.ORANGE_POTION, ItemId.MANA_ELIXIR, 0, 0);
            case BOWMAN -> new Gear(ItemId.GREEN_HUNTERS_ARMOR,
                    ItemId.GREEN_HUNTERS_PANTS,
                    ItemId.GREEN_HUNTER_BOOTS,
                    ItemId.RYDEN,
                    0,
                    ItemId.WHITE_POTION, ItemId.BLUE_POTION, 2060000, 1000);
            case THIEF -> new Gear(ItemId.DARK_BROWN_STEALER,
                    ItemId.DARK_BROWN_STEALER_PANTS,
                    ItemId.BRONZE_CHAIN_BOOTS,
                    ItemId.STEEL_GUARDS,
                    0,
                    ItemId.WHITE_POTION, ItemId.BLUE_POTION,
                    ItemId.SUBI_THROWING_STARS, 500);
            case PIRATE -> new Gear(ItemId.BROWN_POLLARD,
                    0,
                    ItemId.BROWN_PAULIE_BOOTS,
                    ItemId.PISTOL,
                    0,
                    ItemId.WHITE_POTION, ItemId.BLUE_POTION,
                    ItemId.BULLET, 800);
        };
    }
}
