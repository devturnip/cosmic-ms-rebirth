package net.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-backed persistence for {@link AgentMemory}, keyed by character id. Facts and recent
 * dialogue are stored as newline-delimited {@code TEXT} columns. All failures are logged and
 * swallowed so agent lifecycle code is never interrupted by a database problem.
 */
public class AgentMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryStore.class);

    public AgentMemory load(int characterId) {
        AgentMemory memory = new AgentMemory(characterId);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT facts, dialogue FROM agent_memory WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    memory.replaceFacts(splitLines(rs.getString("facts")));
                    memory.replaceDialogue(splitLines(rs.getString("dialogue")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load agent memory for character {}", characterId, e);
        }
        return memory;
    }

    public void save(AgentMemory memory) {
        if (memory == null) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO agent_memory (characterid, facts, dialogue) VALUES (?, ?, ?) " +
                             "ON DUPLICATE KEY UPDATE facts=VALUES(facts), dialogue=VALUES(dialogue)")) {
            ps.setInt(1, memory.getCharacterId());
            ps.setString(2, String.join("\n", memory.facts()));
            ps.setString(3, String.join("\n", memory.recentDialogue()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save agent memory for character {}", memory.getCharacterId(), e);
        }
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
