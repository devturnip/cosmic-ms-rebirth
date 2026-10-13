package net.server.agent;

import client.Job;
import client.SkillFactory;
import client.SkinColor;
import client.inventory.InventoryType;
import config.YamlConfig;
import net.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DB-backed integration test for {@link DbAgentProvisioner}: the real
 * {@link DbAgentProvisioner#provision} / {@link DbAgentProvisioner#destroy} path creates a
 * server-owned account plus a character through {@code Character#insertNewChar} and removes both
 * again.
 * <p>
 * Opt-in: the class name ends in {@code IT}, so the normal {@code *Test} surefire run ignores it.
 * To run it, point it at a live MySQL (schema {@code cosmic}, root/testcosmic123) and pass the
 * port: {@code mvn -Dagent.it.db.port=3307 -Dtest=DbAgentProvisionerIT test}. When the gate
 * property is absent the whole class (including the connection pool) is skipped instantly.
 */
class DbAgentProvisionerIT {

    private static final String PORT_PROPERTY = "agent.it.db.port";
    private static final int TEST_MAP = 100000000; // Henesys
    private static final int TEST_WORLD = 0;

    private static boolean poolReady;

    @BeforeAll
    static void setUpDatabase() {
        final String port = System.getProperty(PORT_PROPERTY);
        // Gate first: never touch the connection pool (which blocks up to 90s on failure) unless
        // the caller explicitly opted into the integration test.
        assumeTrue(port != null && !port.isBlank(),
                "set -D" + PORT_PROPERTY + "=<mysql-port> to run this integration test");

        // config.yaml hardcodes 3306; aim the pool at the requested port before initializing.
        YamlConfig.config.server.DB_URL_FORMAT = "jdbc:mysql://%s:" + port + "/cosmic";
        assumeTrue(DatabaseConnection.initializeConnectionPool(), "MySQL not reachable on port " + port);
        poolReady = true;

        // WZ data drives weapon selection and the starting equip/item ids.
        SkillFactory.loadAllSkills();
        ItemInformationProvider.getInstance();

        // A live server has at least one World; the login-side account/character cache that
        // Client#deleteCharacter authorizes against is only populated through a World.
        TimerManager.getInstance().start();
        if (Server.getInstance().getWorldsSize() == 0) {
            Server.getInstance().addWorld();
        }
    }

    @AfterAll
    static void tearDown() {
        if (poolReady) {
            TimerManager.getInstance().stop();
        }
    }

    @Test
    void provisionsAndDestroysWarriorAndHealer() throws SQLException {
        final DbAgentProvisioner provisioner = new DbAgentProvisioner();
        assertProvisionAndDestroy(provisioner, Job.FIGHTER, 70, Job.CRUSADER.getId());
        assertProvisionAndDestroy(provisioner, Job.CLERIC, 30, Job.CLERIC.getId());
    }

    @Test
    void provisionsDistinctRandomCosmetics() throws SQLException {
        final DbAgentProvisioner provisioner = new DbAgentProvisioner();
        final List<AgentProvisioner.ProvisionedAgent> provisioned = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                final AgentProvisioner.ProvisionedAgent agent =
                        provisioner.provision(30, Job.WARRIOR, TEST_MAP, TEST_WORLD);
                assertNotNull(agent, "provision returned null");
                provisioned.add(agent);
            }
            final CharRow first = loadCharRow(provisioned.get(0).characterId());
            final CharRow second = loadCharRow(provisioned.get(1).characterId());
            assertNotNull(first, "first characters row missing");
            assertNotNull(second, "second characters row missing");
            // Cosmetic collision is astronomically unlikely (thousands of hairs * hundreds of faces * 8 skins).
            assertTrue(first.hair() != second.hair()
                            || first.face() != second.face()
                            || first.skinColor() != second.skinColor(),
                    "two random companions should not share every cosmetic");
        } finally {
            for (AgentProvisioner.ProvisionedAgent agent : provisioned) {
                try {
                    provisioner.destroy(agent);
                } catch (Throwable ignored) {
                    // best effort
                }
                cleanup(agent.characterId(), agent.accountId());
            }
        }
    }

    private void assertProvisionAndDestroy(DbAgentProvisioner provisioner, Job job, int level, int expectedJobId)
            throws SQLException {
        AgentProvisioner.ProvisionedAgent provisioned = null;
        try {
            provisioned = provisioner.provision(level, job, TEST_MAP, TEST_WORLD);
            assertNotNull(provisioned, job + " level " + level + " provisioning returned null");
            assertTrue(provisioned.isValid(), "provisioned agent should be valid: " + provisioned);
            assertEquals(level, provisioned.level(), "provisioned level");
            assertEquals(expectedJobId, provisioned.jobId(), "provisioned job id");

            final CharRow row = loadCharRow(provisioned.characterId());
            assertNotNull(row, "characters row missing for " + provisioned);
            assertEquals(provisioned.name(), row.name(), "characters.name");
            assertEquals(level, row.level(), "characters.level");
            assertEquals(expectedJobId, row.job(), "characters.job");
            assertEquals(provisioned.accountId(), row.accountId(), "characters.accountid");
            assertTrue(CompanionAppearance.hairIds().contains(row.hair()),
                    "characters.hair " + row.hair() + " is not a WZ hair id");
            assertTrue(CompanionAppearance.faceIds().contains(row.face()),
                    "characters.face " + row.face() + " is not a WZ face id");
            assertNotNull(SkinColor.getById(row.skinColor()),
                    "characters.skincolor " + row.skinColor() + " is not a known SkinColor");

            final List<Integer> cashPositions = loadEquippedPositions(provisioned.characterId()).stream()
                    .filter(position -> position <= CompanionAppearance.MAX_CASH_POSITION)
                    .toList();
            assertFalse(cashPositions.isEmpty(),
                    "expected equipped cash items for " + provisioned.name());
            for (int position : cashPositions) {
                assertTrue(position >= CompanionAppearance.MIN_CASH_POSITION,
                        "cash position " + position + " is outside the supported outfit range");
            }

            assertEquals(1, count("SELECT COUNT(*) FROM accounts WHERE id = ?", provisioned.accountId()),
                    "dedicated account row");
            assertTrue(count("SELECT COUNT(*) FROM skills WHERE characterid = ?", provisioned.characterId()) > 0,
                    "skills rows for " + provisioned.characterId());
            assertTrue(count("SELECT COUNT(*) FROM inventoryitems WHERE characterid = ?", provisioned.characterId()) > 0,
                    "inventoryitems rows for " + provisioned.characterId());

            provisioner.destroy(provisioned);
            // destroy is documented as idempotent: a second call (row already gone) must not throw.
            provisioner.destroy(provisioned);

            assertNull(loadCharRow(provisioned.characterId()), "characters row should be gone after destroy");
            assertEquals(0, count("SELECT COUNT(*) FROM accounts WHERE id = ?", provisioned.accountId()),
                    "dedicated account row should be gone after destroy");
            assertEquals(0, count("SELECT COUNT(*) FROM skills WHERE characterid = ?", provisioned.characterId()),
                    "orphan skills rows after destroy");
            assertEquals(0, count("SELECT COUNT(*) FROM inventoryitems WHERE characterid = ?", provisioned.characterId()),
                    "orphan inventoryitems rows after destroy");
        } finally {
            if (provisioned != null) {
                // A failed assertion above must not poison the database for the next run.
                try {
                    provisioner.destroy(provisioned);
                } catch (Throwable ignored) {
                    // best effort
                }
                cleanup(provisioned.characterId(), provisioned.accountId());
            }
        }
    }

    private record CharRow(int id, String name, int level, int job, int accountId,
                           int hair, int face, int skinColor) {
    }

    private static CharRow loadCharRow(int characterId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, name, `level`, job, accountid, hair, face, skincolor FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CharRow(rs.getInt("id"), rs.getString("name"), rs.getInt("level"),
                        rs.getInt("job"), rs.getInt("accountid"), rs.getInt("hair"),
                        rs.getInt("face"), rs.getInt("skincolor"));
            }
        }
    }

    /** Positions of the character's equipped items (regular negatives and worn cash slots). */
    private static List<Integer> loadEquippedPositions(int characterId) throws SQLException {
        final List<Integer> positions = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT position FROM inventoryitems WHERE characterid = ? AND inventorytype = ?")) {
            ps.setInt(1, characterId);
            ps.setInt(2, InventoryType.EQUIPPED.getType());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    positions.add(rs.getInt("position"));
                }
            }
        }
        return positions;
    }

    private static int count(String sql, int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Best-effort removal of any rows a failed assertion may have left behind. */
    private static void cleanup(int characterId, int accountId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            delete(con, "DELETE FROM inventoryequipment WHERE inventoryitemid IN "
                    + "(SELECT inventoryitemid FROM inventoryitems WHERE characterid = ?)", characterId);
            delete(con, "DELETE FROM inventoryitems WHERE characterid = ?", characterId);
            delete(con, "DELETE FROM keymap WHERE characterid = ?", characterId);
            delete(con, "DELETE FROM skills WHERE characterid = ?", characterId);
            delete(con, "DELETE FROM characters WHERE id = ?", characterId);
            delete(con, "DELETE FROM accounts WHERE id = ?", accountId);
        } catch (SQLException e) {
            // Nothing actionable here; the assertion already reports the real failure.
        }
    }

    private static void delete(Connection con, String sql, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
