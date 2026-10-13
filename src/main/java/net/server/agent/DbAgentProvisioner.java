package net.server.agent;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.SkinColor;
import client.creator.CharacterFactoryRecipe;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import constants.skills.Magician;
import constants.skills.Warrior;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
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

    /** Primary stat a role assigns its level-scaled AP to. */
    private enum PrimaryStat { STR, DEX, INT, LUK }

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
        try (Connection con = DatabaseConnection.getConnection()) {
            final int accountId = createAccount(con);
            createdAccountId = accountId;
            if (accountId <= 0) {
                log.warn("Failed to create server-owned account for companion ({} level {})", job, safeLevel);
                return null;
            }

            final String name = generateUniqueCharacterName(con);
            createdName = name;
            if (name == null) {
                deleteAccount(con, accountId);
                log.warn("Failed to find a unique companion name");
                return null;
            }

            final Client client = Client.createMock();
            client.setAccID(accountId);
            client.setAccountName(name);
            client.setWorld(world);

            final Character character = Character.getDefault(client);
            character.setWorld(world);
            character.setJob(job);
            character.setSkinColor(SkinColor.getById(DEFAULT_SKIN));
            character.setGender(DEFAULT_GENDER);
            character.setName(name);
            character.setHair(DEFAULT_HAIR);
            character.setFace(DEFAULT_FACE);

            final CharacterFactoryRecipe recipe = buildRecipe(job, safeLevel, mapId);
            equipFromRecipe(character, recipe);

            if (!character.insertNewChar(recipe)) {
                deleteAccount(con, accountId);
                log.warn("insertNewChar failed for companion {}", name);
                return null;
            }
            createdCharacterId = character.getId();
            applyMeso(con, character.getId(), recipe.getMeso());

            // Populate the login-side account/character cache so Client#deleteCharacter is
            // authorized to remove the row again on dismiss.
            Server.getInstance().loadAccountCharacters(client);

            log.info("Provisioned companion {} (chr {}, acc {}, {} level {}) on map {} world {}",
                    name, character.getId(), accountId, job, safeLevel, mapId, world);
            return new ProvisionedAgent(character.getId(), accountId, name, safeLevel, job.getId());
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
        try {
            final Client client = Client.createMock();
            client.setAccID(agent.accountId());
            client.deleteCharacter(agent.characterId(), agent.accountId());
        } catch (Throwable t) {
            log.warn("Best-effort companion character delete failed for chr {}", agent.characterId(), t);
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            deleteAccount(con, agent.accountId());
        } catch (Throwable t) {
            log.warn("Best-effort companion account delete failed for acc {}", agent.accountId(), t);
        }
    }

    @Override
    public List<String> roles() {
        return AgentRole.names();
    }

    // ------------------------------------------------------------------
    // recipe templates (mirror client/creator/veteran/*Creator.java)
    // ------------------------------------------------------------------

    private static CharacterFactoryRecipe buildRecipe(Job job, int level, int mapId) {
        final AgentRole role = AgentRole.forJob(job);
        final int top;
        final int bottom;
        final int shoes;
        final int weapon;
        final int extraWeapon;
        final int hpPotion;
        final int mpPotion;
        final int ammoItem;
        final int ammoQty;
        final PrimaryStat primary;
        final int hpBase;
        final int hpGain;
        final int mpBase;
        final int mpGain;

        if (role == null) {
            // Unknown job: a bare but playable fallback.
            top = 0;
            bottom = 0;
            shoes = 0;
            weapon = 0;
            extraWeapon = 0;
            hpPotion = ItemId.WHITE_POTION;
            mpPotion = ItemId.BLUE_POTION;
            ammoItem = 0;
            ammoQty = 0;
            primary = PrimaryStat.STR;
            hpBase = 50;
            hpGain = 20;
            mpBase = 5;
            mpGain = 10;
        } else {
            switch (role) {
                case WARRIOR -> {
                    top = ItemId.RED_HWARANG_SHIRT;
                    bottom = ItemId.BLACK_MARTIAL_ARTS_PANTS;
                    shoes = ItemId.MITHRIL_BATTLE_GRIEVES;
                    weapon = ItemId.GLADIUS;
                    extraWeapon = ItemId.MITHRIL_POLE_ARM;
                    hpPotion = ItemId.WHITE_POTION;
                    mpPotion = ItemId.BLUE_POTION;
                    ammoItem = 0;
                    ammoQty = 0;
                    primary = PrimaryStat.STR;
                    hpBase = 50;
                    hpGain = 28;
                    mpBase = 5;
                    mpGain = 6;
                }
                case MAGICIAN, HEALER -> {
                    top = ItemId.PURPLE_FAIRY_TOP;
                    bottom = 0;
                    shoes = ItemId.RED_MAGICSHOES;
                    weapon = ItemId.MITHRIL_WAND;
                    extraWeapon = 0;
                    hpPotion = ItemId.ORANGE_POTION;
                    mpPotion = ItemId.MANA_ELIXIR;
                    ammoItem = 0;
                    ammoQty = 0;
                    primary = PrimaryStat.INT;
                    hpBase = 50;
                    hpGain = 12;
                    mpBase = 5;
                    mpGain = 24;
                }
                case BOWMAN -> {
                    top = ItemId.GREEN_HUNTERS_ARMOR;
                    bottom = ItemId.GREEN_HUNTERS_PANTS;
                    shoes = ItemId.GREEN_HUNTER_BOOTS;
                    weapon = ItemId.RYDEN;
                    extraWeapon = 0;
                    hpPotion = ItemId.WHITE_POTION;
                    mpPotion = ItemId.BLUE_POTION;
                    ammoItem = 2060000; // Bronze Arrow
                    ammoQty = 1000;
                    primary = PrimaryStat.DEX;
                    hpBase = 50;
                    hpGain = 22;
                    mpBase = 5;
                    mpGain = 12;
                }
                case THIEF -> {
                    top = ItemId.DARK_BROWN_STEALER;
                    bottom = ItemId.DARK_BROWN_STEALER_PANTS;
                    shoes = ItemId.BRONZE_CHAIN_BOOTS;
                    weapon = ItemId.STEEL_GUARDS;
                    extraWeapon = 0;
                    hpPotion = ItemId.WHITE_POTION;
                    mpPotion = ItemId.BLUE_POTION;
                    ammoItem = ItemId.SUBI_THROWING_STARS;
                    ammoQty = 500;
                    primary = PrimaryStat.LUK;
                    hpBase = 50;
                    hpGain = 22;
                    mpBase = 5;
                    mpGain = 12;
                }
                case PIRATE -> {
                    top = ItemId.BROWN_POLLARD;
                    bottom = 0;
                    shoes = ItemId.BROWN_PAULIE_BOOTS;
                    weapon = ItemId.PRIME_HANDS;
                    extraWeapon = 0;
                    hpPotion = ItemId.WHITE_POTION;
                    mpPotion = ItemId.BLUE_POTION;
                    ammoItem = ItemId.BULLET;
                    ammoQty = 800;
                    primary = PrimaryStat.DEX;
                    hpBase = 50;
                    hpGain = 26;
                    mpBase = 5;
                    mpGain = 14;
                }
                default -> throw new IllegalStateException("Unhandled role " + role);
            }
        }

        final CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(job, level, mapId, top, bottom, shoes, weapon);

        final int totalAp = 16 + Math.max(0, level - 1) * 5;
        final int primaryValue = Math.min(totalAp - 12, Math.max(4, 4 + (level - 1)));
        final int remainingAp = Math.max(0, totalAp - primaryValue - 12);
        recipe.setStr(4);
        recipe.setDex(4);
        recipe.setInt(4);
        recipe.setLuk(4);
        switch (primary) {
            case STR -> recipe.setStr(primaryValue);
            case DEX -> recipe.setDex(primaryValue);
            case INT -> recipe.setInt(primaryValue);
            case LUK -> recipe.setLuk(primaryValue);
        }
        recipe.setRemainingAp(remainingAp);

        final int baseSp = level >= 10 ? (level - 9) * 3 : 0;
        recipe.setRemainingSp(baseSp);

        int usedSp = addEntrySkills(recipe, job, baseSp, level);
        recipe.setRemainingSp(Math.max(0, baseSp - usedSp));

        recipe.setMaxHp(hpBase + hpGain * Math.max(0, level - 1));
        recipe.setMaxMp(mpBase + mpGain * Math.max(0, level - 1));

        recipe.setMeso(5000 + level * 500); // small stipend; enforced in applyMeso below

        if (extraWeapon > 0) {
            final Item extra = ItemInformationProvider.getInstance().getEquipById(extraWeapon);
            if (extra != null) {
                recipe.addStartingEquipment(extra);
            }
        }
        recipe.addStartingItem(hpPotion, 100, InventoryType.USE);
        recipe.addStartingItem(mpPotion, 100, InventoryType.USE);
        recipe.addStartingItem(ItemId.RELAXER, 1, InventoryType.SETUP);
        if (ammoItem > 0 && ammoQty > 0) {
            recipe.addStartingItem(ammoItem, ammoQty, InventoryType.USE);
        }
        return recipe;
    }

    /**
     * Adds the job's first-job entry skills, mirroring the veteran creators (which only grant the
     * improved HP/MP passives). {@code Heal} is granted to the healer role so it can actually heal.
     *
     * @return the number of SP consumed
     */
    private static int addEntrySkills(CharacterFactoryRecipe recipe, Job job, int availableSp, int level) {
        if (level < 10 || availableSp <= 0) {
            return 0;
        }
        final AgentRole role = AgentRole.forJob(job);
        if (role == null) {
            return 0;
        }
        int used = 0;
        switch (role) {
            case WARRIOR -> {
                used += addSkill(recipe, Warrior.IMPROVED_HPREC, 5, availableSp - used);
                used += addSkill(recipe, Warrior.IMPROVED_MAXHP, 5, availableSp - used);
            }
            case MAGICIAN, HEALER -> {
                used += addSkill(recipe, Magician.IMPROVED_MP_RECOVERY, 5, availableSp - used);
                used += addSkill(recipe, Magician.IMPROVED_MAX_MP_INCREASE, 5, availableSp - used);
                if (role == AgentRole.HEALER) {
                    used += addSkill(recipe, constants.skills.Cleric.HEAL, 5, availableSp - used);
                }
            }
            default -> {
            }
        }
        return used;
    }

    private static int addSkill(CharacterFactoryRecipe recipe, int skillId, int levels, int availableSp) {
        if (levels <= 0 || availableSp <= 0) {
            return 0;
        }
        final Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return 0;
        }
        final int toAdd = Math.min(levels, availableSp);
        recipe.addStartingSkillLevel(skill, toAdd);
        return toAdd;
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
