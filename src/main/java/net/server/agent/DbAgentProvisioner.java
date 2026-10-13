package net.server.agent;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.SkinColor;
import client.creator.CharacterFactoryRecipe;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.id.ItemId;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JDBC-backed {@link AgentProvisioner}: each companion gets a dedicated server-owned account
 * and a character row built through the normal create-character recipe pipeline
 * ({@link CharacterFactoryRecipe} + {@link Character#insertNewChar}).
 * <p>
 * All methods are best-effort and never throw for routine failures; DB work is kept in small
 * package-private helpers so the SQL is auditable and mockable.
 */
public class DbAgentProvisioner implements AgentProvisioner {
    private static final Logger log = LoggerFactory.getLogger(DbAgentProvisioner.class);

    static final int NAME_MAX_LENGTH = 13; // accounts.name / characters.name are varchar(13)
    private static final int ACCOUNT_ATTEMPTS = 6;
    private static final int NAME_ATTEMPTS = 8;

    private static final int DEFAULT_FACE = 20000;
    private static final int DEFAULT_HAIR = 30000;
    private static final int DEFAULT_SKIN = 0;
    private static final int DEFAULT_GENDER = 0; // male

    /** Cached equip item ids from WZ (see {@link #equipCandidateIds(ItemInformationProvider)}). */
    private static volatile List<Integer> equipCandidateCache;

    @Override
    public ProvisionedAgent provision(int level, Job job, int mapId, int world) {
        if (job == null) {
            log.warn("Refusing to provision companion: no job supplied");
            return null;
        }
        final int safeLevel = Math.max(1, level);
        int createdAccountId = -1;
        int createdCharacterId = -1;
        String createdName = null;
        try {
            final int accountId;
            final String name;
            // Scope this connection tightly: insertNewChar()/loadAccountCharacters() open their own
            // connections, and the pool is small, so holding one while they block would deadlock
            // concurrent /agents requests.
            try (Connection con = DatabaseConnection.getConnection()) {
                accountId = createAccount(con);
                if (accountId <= 0) {
                    log.warn("Failed to create server-owned account for companion ({} level {})", job, safeLevel);
                    return null;
                }
                createdAccountId = accountId;

                name = generateUniqueCharacterName(con);
                if (name == null) {
                    deleteAccount(con, accountId);
                    createdAccountId = -1; // already cleaned up; do not delete twice in rollback
                    log.warn("Failed to find a unique companion name");
                    return null;
                }
                createdName = name;
            }

            final Client client = Client.createMock();
            client.setAccID(accountId);
            client.setAccountName(name);
            client.setWorld(world);

            final CompanionTemplate.Build build = CompanionTemplate.buildFor(job, safeLevel);
            final Character character = Character.getDefault(client);
            character.setWorld(world);
            character.setJob(build.job());
            character.setSkinColor(SkinColor.getById(DEFAULT_SKIN));
            character.setGender(DEFAULT_GENDER);
            character.setName(name);
            character.setHair(DEFAULT_HAIR);
            character.setFace(DEFAULT_FACE);

            final CharacterFactoryRecipe recipe = buildRecipe(build, mapId);
            equipFromRecipe(character, recipe);

            if (!character.insertNewChar(recipe)) {
                log.warn("insertNewChar failed for companion {}", name);
                rollbackProvision(createdCharacterId, createdAccountId, name, safeLevel, job);
                return null;
            }
            createdCharacterId = character.getId();

            try (Connection con = DatabaseConnection.getConnection()) {
                applyMeso(con, character.getId(), recipe.getMeso());
            }

            // Populate the login-side account/character cache so Client#deleteCharacter is
            // authorized to remove the row again on dismiss.
            Server.getInstance().loadAccountCharacters(client);

            log.info("Provisioned companion {} (chr {}, acc {}, {} level {}) on map {} world {}",
                    name, character.getId(), accountId, build.job(), safeLevel, mapId, world);
            return new ProvisionedAgent(character.getId(), accountId, name, safeLevel, build.job().getId());
        } catch (Throwable t) {
            log.error("Failed to provision companion ({} level {}, map {}, world {})", job, safeLevel, mapId, world, t);
            rollbackProvision(createdCharacterId, createdAccountId, createdName, safeLevel, job);
            return null;
        }
    }

    /** Best-effort rollback of a partially provisioned companion (never throws). */
    private void rollbackProvision(int characterId, int accountId, String name, int level, Job job) {
        try {
            if (characterId > 0) {
                destroy(new ProvisionedAgent(characterId, accountId, name == null ? "?" : name, level, job.getId()));
            } else if (accountId > 0) {
                try (Connection con = DatabaseConnection.getConnection()) {
                    deleteAccount(con, accountId);
                }
            }
        } catch (Throwable t) {
            log.warn("Failed to roll back companion provisioning (chr {}, acc {})", characterId, accountId, t);
        }
    }

    @Override
    public void destroy(ProvisionedAgent agent) {
        if (agent == null || !agent.isValid()) {
            return;
        }
        boolean characterGone = false;
        try {
            final Client client = Client.createMock();
            client.setAccID(agent.accountId());
            // Client#deleteCharacter is gated by Server#haveCharacterEntry; load the account into
            // the login cache first so the delete is authorized for this server-owned account.
            Server.getInstance().loadAccountCharacters(client);
            characterGone = client.deleteCharacter(agent.characterId(), agent.accountId());
        } catch (Throwable t) {
            log.warn("Best-effort companion character delete failed for chr {}", agent.characterId(), t);
        }
        if (!characterGone) {
            // deleteCharacter also returns false when the row is already gone; only keep the
            // account if the character really still exists (avoids a dangling accountid).
            characterGone = !characterExistsById(agent.characterId());
        }
        if (!characterGone) {
            log.warn("Companion character {} still exists; keeping account {} to avoid a dangling reference",
                    agent.characterId(), agent.accountId());
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            deleteAccount(con, agent.accountId());
        } catch (Throwable t) {
            log.warn("Best-effort companion account delete failed for acc {}", agent.accountId(), t);
        }
    }

    /** Whether a character row still exists, used to decide if the account can be deleted. */
    boolean characterExistsById(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("Failed to check companion character {}", characterId, e);
            return true; // assume it still exists; keeping the account is the safe choice
        }
    }

    @Override
    public List<String> roles() {
        return AgentRole.names();
    }

    // ------------------------------------------------------------------
    // recipe templates
    // ------------------------------------------------------------------

    private static CharacterFactoryRecipe buildRecipe(CompanionTemplate.Build build, int mapId) {
        final CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(build.job(), build.level(), mapId,
                build.top(), build.bottom(), build.shoes(), selectWeapon(build));

        recipe.setStr(build.str());
        recipe.setDex(build.dex());
        recipe.setInt(build.int_());
        recipe.setLuk(build.luk());
        recipe.setRemainingAp(build.remainingAp());

        int uncappedSp = 0;
        int spentSp = 0;
        for (CompanionTemplate.SkillGrant grant : build.skills()) {
            uncappedSp += grant.level();
            final Skill skill = SkillFactory.getSkill(grant.skillId());
            if (skill == null) {
                continue;
            }
            final int level = Math.min(grant.level(), skill.getMaxLevel());
            if (level > 0) {
                recipe.addStartingSkillLevel(skill, level);
                spentSp += level;
            }
        }
        // Build.remainingSp was computed against the uncapped requests; recompute it against the
        // levels actually persisted so a WZ cap (known or newly discovered) never inflates SP.
        final int totalSp = build.remainingSp() + uncappedSp;
        recipe.setRemainingSp(Math.max(0, totalSp - spentSp));

        recipe.setMaxHp(build.maxHp());
        recipe.setMaxMp(build.maxMp());
        recipe.setMeso(build.meso()); // small stipend; enforced in applyMeso below

        if (build.extraWeapon() > 0) {
            final Item extra = ItemInformationProvider.getInstance().getEquipById(build.extraWeapon());
            if (extra != null) {
                recipe.addStartingEquipment(extra);
            }
        }
        recipe.addStartingItem(build.hpPotion(), 100, InventoryType.USE);
        recipe.addStartingItem(build.mpPotion(), 100, InventoryType.USE);
        recipe.addStartingItem(ItemId.RELAXER, 1, InventoryType.SETUP);
        if (build.ammoItem() > 0 && build.ammoQty() > 0) {
            recipe.addStartingItem(build.ammoItem(), build.ammoQty(), InventoryType.USE);
        }
        return recipe;
    }

    /**
     * Picks the highest-requirement weapon from {@link ItemInformationProvider} whose weapon type,
     * job and stat requirements the build satisfies (tie-break: highest attack). Falls back to the
     * role's fixed weapon when the WZ data is unavailable or nothing matches.
     */
    static int selectWeapon(CompanionTemplate.Build build) {
        final int fallback = build.weapon();
        if (build.role() == null) {
            return fallback;
        }
        final Set<WeaponType> allowed = CompanionTemplate.roleWeaponTypes(build.role(), build.level());
        final int jobMask = CompanionTemplate.jobMask(build.role());
        final ItemInformationProvider ii;
        final List<Integer> items;
        try {
            ii = ItemInformationProvider.getInstance();
            items = equipCandidateIds(ii);
        } catch (Throwable t) {
            log.debug("WZ item data unavailable for companion weapon; using fallback {}", fallback, t);
            return fallback;
        }
        int bestId = fallback;
        int bestLevel = -1;
        int bestAtk = -1;
        for (int itemId : items) {
            try {
                if (!allowed.contains(ii.getWeaponType(itemId))) {
                    continue;
                }
                final Integer reqLevel = ii.getEquipLevelReq(itemId);
                if (reqLevel == null || reqLevel > build.level()) {
                    continue;
                }
                final Map<String, Integer> stats = ii.getEquipStats(itemId);
                if (stats == null || isCash(stats) || !jobAllowed(stats, jobMask) || !statsAllowed(stats, build)) {
                    continue;
                }
                final Item item = ii.getEquipById(itemId);
                if (!(item instanceof Equip equip)) {
                    continue;
                }
                final int atk = equip.getWatk() + equip.getMatk();
                if (reqLevel > bestLevel || (reqLevel == bestLevel && atk > bestAtk)) {
                    bestLevel = reqLevel;
                    bestAtk = atk;
                    bestId = itemId;
                }
            } catch (Throwable t) {
                log.trace("Skipping companion weapon candidate {}", itemId, t);
            }
        }
        return bestId;
    }

    /** Equip item ids from the WZ item-name list; cached so provisioning does not rescan it. */
    private static List<Integer> equipCandidateIds(ItemInformationProvider ii) {
        List<Integer> cached = equipCandidateCache;
        if (cached == null) {
            final List<Integer> ids = new ArrayList<>();
            for (Pair<Integer, String> entry : ii.getAllItems()) {
                final int itemId = entry.getLeft();
                if (itemId / 1000000 == 1) {
                    ids.add(itemId);
                }
            }
            cached = List.copyOf(ids);
            equipCandidateCache = cached;
        }
        return cached;
    }

    private static boolean isCash(Map<String, Integer> stats) {
        final Integer cash = stats.get("cash");
        return cash != null && cash == 1;
    }

    private static boolean jobAllowed(Map<String, Integer> stats, int jobMask) {
        final Integer reqJob = stats.get("reqJob");
        return reqJob == null || reqJob == 0 || (reqJob & jobMask) != 0;
    }

    private static boolean statsAllowed(Map<String, Integer> stats, CompanionTemplate.Build build) {
        return req(stats, "reqSTR") <= build.str()
                && req(stats, "reqDEX") <= build.dex()
                && req(stats, "reqINT") <= build.int_()
                && req(stats, "reqLUK") <= build.luk()
                && req(stats, "reqPOP") <= 0; // companions have no fame
    }

    private static int req(Map<String, Integer> stats, String key) {
        final Integer value = stats.get(key);
        return value == null ? 0 : value;
    }

    /** Equips the recipe's top/bottom/shoes/weapon exactly as {@code CharacterFactory} does. */
    private static void equipFromRecipe(Character character, CharacterFactoryRecipe recipe) {
        final Inventory equipped = character.getInventory(InventoryType.EQUIPPED);
        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (recipe.getTop() > 0) {
            equip(equipped, ii.getEquipById(recipe.getTop()), (byte) -5);
        }
        if (recipe.getBottom() > 0) {
            equip(equipped, ii.getEquipById(recipe.getBottom()), (byte) -6);
        }
        if (recipe.getShoes() > 0) {
            equip(equipped, ii.getEquipById(recipe.getShoes()), (byte) -7);
        }
        if (recipe.getWeapon() > 0) {
            final Item weapon = ii.getEquipById(recipe.getWeapon());
            if (weapon != null) {
                weapon.setPosition((byte) -11);
                equipped.addItemFromDB(weapon.copy());
            }
        }
    }

    private static void equip(Inventory equipped, Item item, byte position) {
        if (item == null) {
            return;
        }
        item.setPosition(position);
        equipped.addItemFromDB(item);
    }

    // ------------------------------------------------------------------
    // DB helpers (package-private seam)
    // ------------------------------------------------------------------

    /** Inserts a server-owned account and returns its generated id, or -1 on failure. */
    int createAccount(Connection con) {
        for (int attempt = 0; attempt < ACCOUNT_ATTEMPTS; attempt++) {
            final String name = randomAccountName();
            if (name == null) {
                return -1;
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (name, password, characterslots) VALUES (?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, randomAlphanumeric(64)); // unusable, random password
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    continue; // name collision, try another random name
                }
                log.error("Failed to insert companion account", e);
                return -1;
            }
        }
        return -1;
    }

    boolean deleteAccount(Connection con, int accountId) {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Failed to delete companion account {}", accountId, e);
            return false;
        }
    }

    /** Updates the persisted meso stipend (insertNewChar otherwise hardcodes 1,000,000). */
    boolean applyMeso(Connection con, int characterId, int meso) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, Math.max(0, meso));
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Failed to set companion {} meso", characterId, e);
            return false;
        }
    }

    boolean characterNameExists(Connection con, String name) {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check companion name {}", name, e);
            return true; // treat as taken so we do not risk a collision
        }
    }

    String generateUniqueCharacterName(Connection con) {
        final String prefix = AgentConfig.namePrefix();
        if (prefix.length() > NAME_MAX_LENGTH - 3) {
            log.warn("LLM_AGENT_NAME_PREFIX '{}' leaves no room for a 3-char companion name", prefix);
            return null;
        }
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            final String base = randomNameBase(prefix.length());
            if (base == null || !base.matches("[a-zA-Z0-9]{3,}")) {
                continue;
            }
            final String name = prefix + base;
            if (name.length() > NAME_MAX_LENGTH || characterNameExists(con, name)) {
                continue;
            }
            return name;
        }
        return null;
    }

    private static boolean isDuplicateKey(SQLException e) {
        return e instanceof SQLIntegrityConstraintViolationException || e.getErrorCode() == 1062;
    }

    private static String randomAccountName() {
        final String prefix = AgentConfig.accountPrefix();
        final int prefixLength = Math.min(prefix.length(), NAME_MAX_LENGTH - 1);
        final String safePrefix = prefix.substring(0, prefixLength);
        final int baseLength = Math.max(1, NAME_MAX_LENGTH - safePrefix.length());
        return safePrefix + randomAlphanumeric(baseLength);
    }

    private static String randomNameBase(int prefixLength) {
        final int maxBase = Math.min(6, NAME_MAX_LENGTH - prefixLength);
        if (maxBase < 3) {
            return null;
        }
        final int length = 3 + ThreadLocalRandom.current().nextInt(maxBase - 2);
        return randomAlphanumeric(length);
    }

    static String randomAlphanumeric(int length) {
        if (length <= 0) {
            return "";
        }
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        final StringBuilder sb = new StringBuilder(length);
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
