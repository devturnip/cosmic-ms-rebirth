package net.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-backed persistence for players who have opted out of AI-agent interaction, keyed by
 * character id ({@code agent_optout} table). All failures are logged and swallowed so a
 * database problem never breaks login, spawning or the opt-out command.
 */
public class AgentOptoutStore {
    private static final Logger log = LoggerFactory.getLogger(AgentOptoutStore.class);

    /** Every character id that has opted out. */
    public Set<Integer> loadAll() {
        Set<Integer> optedOut = ConcurrentHashMap.newKeySet();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT characterid FROM agent_optout");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                optedOut.add(rs.getInt("characterid"));
            }
        } catch (Exception e) {
            log.warn("Failed to load agent opt-out list; continuing with an empty set", e);
        }
        return optedOut;
    }

    /** Adds or removes the opt-out row for {@code characterId}. */
    public void set(int characterId, boolean optedOut) {
        try (Connection con = DatabaseConnection.getConnection()) {
            if (optedOut) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO agent_optout (characterid) VALUES (?) " +
                                "ON DUPLICATE KEY UPDATE characterid = characterid")) {
                    ps.setInt(1, characterId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM agent_optout WHERE characterid = ?")) {
                    ps.setInt(1, characterId);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to persist agent opt-out state for character {}", characterId, e);
        }
    }
}
