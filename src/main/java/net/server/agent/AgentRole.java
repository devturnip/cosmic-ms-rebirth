package net.server.agent;

import client.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Canonical set of companion roles and their base {@link Job}, shared by the provisioning
 * template ({@link DbAgentProvisioner}) and the selection policy
 * ({@link AgentCompanionService}).
 * <p>
 * Roles are deliberately coarse: a companion is built from a first/second job recipe, not a
 * full 4th-job skill build (see the plan's "recipe breadth" note).
 */
enum AgentRole {
    WARRIOR("warrior", Job.WARRIOR),
    MAGICIAN("magician", Job.MAGICIAN),
    HEALER("healer", Job.CLERIC),
    BOWMAN("bowman", Job.BOWMAN),
    THIEF("thief", Job.THIEF),
    PIRATE("pirate", Job.PIRATE);

    private final String roleName;
    private final Job job;

    AgentRole(String roleName, Job job) {
        this.roleName = roleName;
        this.job = job;
    }

    String roleName() {
        return roleName;
    }

    Job job() {
        return job;
    }

    /** Canonical role names, in a stable order, for {@code /agents list}. */
    static List<String> names() {
        return List.of(WARRIOR.roleName, MAGICIAN.roleName, HEALER.roleName,
                BOWMAN.roleName, THIEF.roleName, PIRATE.roleName);
    }

    /** Resolves a requested role, accepting a few common aliases; {@code null} if unknown. */
    static AgentRole fromName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase()) {
            case "warrior", "fighter", "spearman", "page" -> WARRIOR;
            case "magician", "mage", "wizard", "wiz" -> MAGICIAN;
            case "healer", "cleric", "priest", "bishop" -> HEALER;
            case "bowman", "archer", "hunter", "crossbowman", "crossbow" -> BOWMAN;
            case "thief", "rogue", "assassin", "bandit", "nightlord" -> THIEF;
            case "pirate", "brawler", "gunslinger", "corsair", "buccaneer" -> PIRATE;
            default -> null;
        };
    }

    /** Maps an arbitrary job onto the closest companion role. */
    static AgentRole forJob(Job job) {
        if (job == null) {
            return null;
        }
        return switch (job) {
            case WARRIOR, FIGHTER, CRUSADER, HERO, PAGE, WHITEKNIGHT, PALADIN,
                    SPEARMAN, DRAGONKNIGHT, DARKKNIGHT -> WARRIOR;
            case MAGICIAN, FP_WIZARD, FP_MAGE, FP_ARCHMAGE, IL_WIZARD, IL_MAGE, IL_ARCHMAGE -> MAGICIAN;
            case CLERIC, PRIEST, BISHOP -> HEALER;
            case BOWMAN, HUNTER, RANGER, BOWMASTER, CROSSBOWMAN, SNIPER, MARKSMAN -> BOWMAN;
            case THIEF, ASSASSIN, HERMIT, NIGHTLORD, BANDIT, CHIEFBANDIT, SHADOWER -> THIEF;
            case PIRATE, BRAWLER, MARAUDER, BUCCANEER, GUNSLINGER, OUTLAW, CORSAIR -> PIRATE;
            default -> null;
        };
    }

    /**
     * Companion roles that complement {@code playerJob}, best first: every role except the
     * player's own, so a summoned companion always brings a different class to the party.
     * A healer is the best partner for anyone who cannot heal; a healer's own best partner is a
     * frontline warrior. Unknown/classless jobs get the full roster.
     * <p>
     * This replaces the old fixed warrior/healer pairing, which meant a non-warrior player could
     * only ever summon a warrior (and happened never to see magician/bowman/thief/pirate).
     */
    static List<AgentRole> complements(Job playerJob) {
        AgentRole playerRole = forJob(playerJob);
        List<AgentRole> ordered = new ArrayList<>(values().length);
        if (playerRole != HEALER) {
            ordered.add(HEALER);
        }
        if (playerRole != WARRIOR) {
            ordered.add(WARRIOR);
        }
        for (AgentRole role : values()) {
            if (role != playerRole && role != HEALER && role != WARRIOR) {
                ordered.add(role);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Picks a complement from the ordered list produced by {@link #complements(Job)}. The first
     * (best-synergy) entry is twice as likely as each of the rest, so the ideal partner usually
     * shows up while every other class stays reachable.
     *
     * @return the chosen role, or {@code null} if {@code ordered} is null/empty
     */
    static AgentRole pickComplement(List<AgentRole> ordered, Random random) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        int roll = random.nextInt(ordered.size() + 1);
        return roll < 2 ? ordered.get(0) : ordered.get(roll - 1);
    }

    static AgentRole random() {
        AgentRole[] roles = values();
        return roles[ThreadLocalRandom.current().nextInt(roles.length)];
    }
}
