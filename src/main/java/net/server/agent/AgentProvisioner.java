package net.server.agent;

import client.Job;

import java.util.List;

/**
 * Creates and destroys server-owned, <em>ephemeral</em> agent characters on demand.
 * <p>
 * Player-facing companions are not borrowed from human accounts: the provisioner allocates a
 * dedicated server-owned account, builds a {@code Character} row through the normal
 * create-character recipe pipeline ({@code CharacterFactoryRecipe} +
 * {@code Character#insertNewChar}), and returns it. The companion is deleted again when it is
 * dismissed. GM-spawned agents keep using the existing persistent-character path and do not go
 * through this interface.
 */
public interface AgentProvisioner {

    /**
     * A freshly provisioned companion. {@code characterId} is the generated character row id,
     * {@code accountId} is the dedicated server-owned account it lives on, and {@code world} is
     * the world it was created in (needed so its connection-less mock client can resolve the
     * world server when the character is deleted again).
     */
    record ProvisionedAgent(int characterId, int accountId, String name, int level, int jobId, int world) {
        public boolean isValid() {
            return characterId > 0 && accountId > 0 && name != null && !name.isBlank();
        }
    }

    /**
     * Provisions a new server-owned character at the requested level and job, placed on
     * {@code mapId} in {@code world}.
     *
     * @return the created companion, or {@code null} if provisioning failed (no slots, name
     *         collision, database error). Implementations must never throw for a routine
     *         failure.
     */
    ProvisionedAgent provision(int level, Job job, int mapId, int world);

    /**
     * Deletes a provisioned companion and its dedicated account. Best-effort: implementations
     * must never throw and must be safe to call twice.
     */
    void destroy(ProvisionedAgent agent);

    /** Roles this provisioner can build, for {@code /agents list}. */
    default List<String> roles() {
        return List.of();
    }

    /**
     * Deletes any server-owned companion accounts left over from a previous run (e.g. after an
     * ungraceful shutdown). Best-effort; returns the number of accounts cleaned up.
     */
    default int reapOrphans() {
        return 0;
    }
}
