package net.server.agent;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Orchestration and policy for player-facing ephemeral companions: eligibility, caps and
 * cooldowns, role/level choice, provisioning, spawning, following and party formation.
 * <p>
 * All collaborators are constructor-injected so the service can be unit-tested without a DB:
 * the {@link AgentProvisioner}, a small spawn/count/despawn {@link CompanionRuntime} seam (the
 * default wraps {@link AgentManager}), a {@link CompanionPartyService}, and a clock.
 * <p>
 * Every public entry point is defensive: it never throws to a command; failures come back as a
 * {@link Result} with a player-readable message.
 */
public class AgentCompanionService {
    private static final Logger log = LoggerFactory.getLogger(AgentCompanionService.class);

    /** Outcome of a player companion request. */
    public enum Status {
        OK, DISABLED, NOT_ELIGIBLE, BAD_MAP, CAPPED, COOLDOWN, CAPACITY,
        UNKNOWN_ROLE, PROVISION_FAILED, SPAWN_FAILED, DISMISSED, NOTHING_TO_DISMISS, ERROR
    }

    public record Result(Status status, AgentSession session, String message) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * Minimal seam over {@link AgentManager} for spawning, ownership counting and despawn.
     */
    public interface CompanionRuntime {
        AgentSession spawn(String name, int world, int channel, AgentBrain brain, boolean ephemeral, int ownerPlayerId,
                           AgentProvisioner.ProvisionedAgent provisioned);

        int countOwnedBy(int ownerPlayerId);

        int activeCount();

        List<AgentSession> despawnOwnedBy(int ownerPlayerId);
    }

    /** Default runtime backed by the singleton {@link AgentManager}. */
    public static final class ManagerRuntime implements CompanionRuntime {
        @Override
        public AgentSession spawn(String name, int world, int channel, AgentBrain brain, boolean ephemeral, int ownerPlayerId,
                                  AgentProvisioner.ProvisionedAgent provisioned) {
            return AgentManager.getInstance().spawn(name, world, channel, brain, null, ephemeral, ownerPlayerId, provisioned);
        }

        @Override
        public int countOwnedBy(int ownerPlayerId) {
            return AgentManager.getInstance().countOwnedBy(ownerPlayerId);
        }

        @Override
        public int activeCount() {
            return AgentManager.getInstance().getSessions().size();
        }

        @Override
        public List<AgentSession> despawnOwnedBy(int ownerPlayerId) {
            return AgentManager.getInstance().despawnOwnedBy(ownerPlayerId);
        }
    }

    private final AgentProvisioner provisioner;
    private final CompanionRuntime runtime;
    private final CompanionPartyService partyService;
    private final LongSupplier clock;
    private final Map<Integer, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** Per-player request locks: serializes cap/cooldown checks with provisioning/spawn. */
    private final Map<Integer, Object> requestLocks = new ConcurrentHashMap<>();

    public AgentCompanionService(AgentProvisioner provisioner, CompanionRuntime runtime,
                                 CompanionPartyService partyService, LongSupplier clock) {
        this.provisioner = provisioner;
        this.runtime = runtime;
        this.partyService = partyService;
        this.clock = clock;
    }

    /** Production instance wired to real provisioning/spawning and the system clock. */
    public static AgentCompanionService createDefault() {
        return new AgentCompanionService(new DbAgentProvisioner(), new ManagerRuntime(),
                new CompanionPartyService(), System::currentTimeMillis);
    }

    // ------------------------------------------------------------------
    // /agents find
    // ------------------------------------------------------------------

    /**
     * Validates a player's request, provisions a companion and brings it online beside the
     * player. The validation order matches the plan: feature switch, eligibility, map, per-player
     * cap, cooldown, global capacity, then provisioning.
     */
    public Result find(Character player, String requestedRole) {
        if (!AgentConfig.playerSpawn()) {
            return new Result(Status.DISABLED, null, "Player companions are disabled on this server.");
        }
        if (player == null || player.getClient() == null) {
            return new Result(Status.NOT_ELIGIBLE, null, "You cannot summon a companion right now.");
        }
        // Serialize per player: the cap/cooldown check-then-act must not race concurrent requests,
        // and the (slow) provisioning must not let a second request squeeze past the cap.
        synchronized (requestLocks.computeIfAbsent(player.getId(), k -> new Object())) {
            AgentProvisioner.ProvisionedAgent provisioned = null;
            boolean spawned = false;
            try {
                if (!player.isAlive() || player.getMap() == null || player.isChangingMaps()
                        || player.getEventInstance() != null
                        || player.getTrade() != null
                        || (player.getCashShop() != null && player.getCashShop().isOpened())
                        || AgentManager.getInstance().isOptedOut(player.getId())) {
                    return new Result(Status.NOT_ELIGIBLE, null,
                            "You cannot summon a companion while dead, transitioning, trading, in the cash shop or in an event.");
                }
                if (player.getMap().isEventMap()) {
                    return new Result(Status.BAD_MAP, null, "Companions cannot be summoned on an event map.");
                }

                final int owned = runtime.countOwnedBy(player.getId());
                if (owned >= AgentConfig.maxPerPlayer()) {
                    return new Result(Status.CAPPED, null,
                            "You already have " + owned + " companion(s) (max " + AgentConfig.maxPerPlayer() + ").");
                }

                final long now = clock.getAsLong();
                final long cooldownRemaining = cooldownRemaining(player.getId(), now);
                if (cooldownRemaining > 0) {
                    return new Result(Status.COOLDOWN, null,
                            "Please wait " + ((cooldownRemaining + 999) / 1000) + "s before summoning again.");
                }

                if (runtime.activeCount() >= AgentConfig.maxAgents()) {
                    return new Result(Status.CAPACITY, null,
                            "All companion slots are in use right now. Try again shortly.");
                }

                final AgentRole role = chooseRole(player, requestedRole);
                if (role == null) {
                    if (requestedRole != null && !requestedRole.isBlank()) {
                        return new Result(Status.UNKNOWN_ROLE, null,
                                "Unknown role '" + requestedRole + "'. Available: " + String.join(", ", provisioner.roles()) + ".");
                    }
                    return new Result(Status.PROVISION_FAILED, null, "No companion roles are available.");
                }

                // Consume the cooldown as soon as we commit to provisioning, so a failing backend
                // cannot be hammered.
                lastRequestAt.put(player.getId(), now);

                final int level = matchLevel(player.getLevel());
                provisioned = provisioner.provision(level, role.job(), player.getMapId(), player.getWorld());
                if (provisioned == null || !provisioned.isValid()) {
                    return new Result(Status.PROVISION_FAILED, null,
                            "Failed to prepare a companion. Check the server log.");
                }

                final AgentSession session;
                try {
                    session = runtime.spawn(provisioned.name(), player.getWorld(),
                            player.getClient().getChannel(), AgentBrainFactory.create(provisioned.name()),
                            true, player.getId(), provisioned);
                } catch (Throwable t) {
                    log.error("Companion spawn threw for {}", provisioned.name(), t);
                    provisioner.destroy(provisioned);
                    return new Result(Status.SPAWN_FAILED, null,
                            "Failed to bring the companion online. Check the server log.");
                }
                if (session == null) {
                    provisioner.destroy(provisioned); // never leave a half-spawned character behind
                    return new Result(Status.SPAWN_FAILED, null,
                            "Failed to bring the companion online. Check the server log.");
                }
                spawned = true;
                session.setManualFollow(player.getId());
                try {
                    partyService.autoParty(player, session);
                } catch (Throwable t) {
                    log.warn("Companion {} spawned but auto-party failed", provisioned.name(), t);
                }
                return new Result(Status.OK, session,
                        "Summoned " + provisioned.name() + " (" + role.roleName() + ", level " + level + ").");
            } catch (Throwable t) {
                if (!spawned && provisioned != null) {
                    provisioner.destroy(provisioned); // do not orphan a provisioned character
                }
                log.error("Companion find failed", t);
                return new Result(Status.ERROR, null, "Failed to summon a companion. Check the server log.");
            }
        }
    }

    // ------------------------------------------------------------------
    // /agents dismiss
    // ------------------------------------------------------------------

    /** Despawns every ephemeral companion summoned by {@code player}; {@link AgentManager} deletes them. */
    public Result dismiss(Character player) {
        if (player == null) {
            return new Result(Status.NOT_ELIGIBLE, null, "No player.");
        }
        synchronized (requestLocks.computeIfAbsent(player.getId(), k -> new Object())) {
            try {
                final List<AgentSession> owned = runtime.despawnOwnedBy(player.getId());
                if (owned == null || owned.isEmpty()) {
                    return new Result(Status.NOTHING_TO_DISMISS, null, "You have no active companion.");
                }
                return new Result(Status.DISMISSED, null,
                        "Dismissed " + owned.size() + " companion" + (owned.size() == 1 ? "" : "s") + ".");
            } catch (Throwable t) {
                log.error("Companion dismiss failed", t);
                return new Result(Status.ERROR, null, "Failed to dismiss your companion. Check the server log.");
            }
        }
    }

    // ------------------------------------------------------------------
    // /agents list
    // ------------------------------------------------------------------

    /** Human-readable snapshot of availability for {@code /agents list}. */
    public List<String> describeAvailable(Character player) {
        final List<String> lines = new ArrayList<>();
        try {
            final List<String> roles = provisioner.roles();
            lines.add("Roles: " + (roles == null || roles.isEmpty() ? "(none)" : String.join(", ", roles)));
            if (player != null) {
                lines.add("Your companions: " + runtime.countOwnedBy(player.getId())
                        + "/" + AgentConfig.maxPerPlayer());
                final long remaining = cooldownRemaining(player.getId(), clock.getAsLong());
                if (remaining > 0) {
                    lines.add("Cooldown: " + ((remaining + 999) / 1000) + "s");
                }
            }
            lines.add("Global capacity: " + runtime.activeCount() + "/" + AgentConfig.maxAgents());
            if (!AgentConfig.playerSpawn()) {
                lines.add("Player companions are disabled (set LLM_AGENT_PLAYER_SPAWN: true).");
            }
        } catch (Throwable t) {
            log.warn("Failed to describe companion availability", t);
            lines.add("Companion status is temporarily unavailable.");
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // policy helpers
    // ------------------------------------------------------------------

    /** The player's level, optionally rounded down to a 5-level band under {@code BAND5}. */
    public static int matchLevel(int playerLevel) {
        final int level = Math.max(1, playerLevel);
        if ("BAND5".equalsIgnoreCase(AgentConfig.levelMatch())) {
            return Math.max(1, (level / 5) * 5);
        }
        return level;
    }

    /** Role that complements {@code playerJob} (see {@link AgentRole#complement(client.Job)}). */
    static AgentRole complementRole(client.Job playerJob) {
        return AgentRole.complement(playerJob);
    }

    private AgentRole chooseRole(Character player, String requestedRole) {
        final List<String> available = provisioner.roles();
        final boolean hasAvailable = available != null && !available.isEmpty();

        if (requestedRole != null && !requestedRole.isBlank()) {
            final AgentRole role = AgentRole.fromName(requestedRole);
            if (role == null || (hasAvailable && !available.contains(role.roleName()))) {
                return null;
            }
            return role;
        }

        if (AgentConfig.complementJob()) {
            final AgentRole complement = complementRole(player.getJob());
            if (!hasAvailable || available.contains(complement.roleName())) {
                return complement;
            }
        }

        final AgentRole random = AgentRole.random();
        if (!hasAvailable || available.contains(random.roleName())) {
            return random;
        }
        for (String name : available) {
            final AgentRole role = AgentRole.fromName(name);
            if (role != null) {
                return role;
            }
        }
        return null;
    }

    private long cooldownRemaining(int playerId, long now) {
        final Long last = lastRequestAt.get(playerId);
        if (last == null) {
            return 0;
        }
        final long elapsed = now - last;
        if (elapsed < 0 || elapsed >= AgentConfig.requestCooldownMs()) {
            return 0;
        }
        return AgentConfig.requestCooldownMs() - elapsed;
    }
}
