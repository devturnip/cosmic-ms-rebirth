package net.server.agent;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming tests for player companions: {@code Agent-<fun word>}, within the varchar(13) limit,
 * with bounded uniqueness retries, plus the dual-prefix acceptance used by {@link AgentManager}.
 */
class AgentCompanionNamingTest {

    private String originalNamePrefix;
    private String originalCompanionPrefix;

    @BeforeEach
    void saveConfig() {
        originalNamePrefix = YamlConfig.config.server.LLM_AGENT_NAME_PREFIX;
        originalCompanionPrefix = YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX;
    }

    @AfterEach
    void restoreConfig() {
        YamlConfig.config.server.LLM_AGENT_NAME_PREFIX = originalNamePrefix;
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = originalCompanionPrefix;
    }

    /** A provisioner whose name-existence check is scripted, so no DB is needed. */
    private static class StubProvisioner extends DbAgentProvisioner {
        private final NameCheck check;
        int calls;

        StubProvisioner(NameCheck check) {
            this.check = check;
        }

        @Override
        boolean characterNameExists(Connection con, String name) {
            calls++;
            return check.taken(name);
        }
    }

    @FunctionalInterface
    private interface NameCheck {
        boolean taken(String name);
    }

    @Test
    void companionNamesUseFunWordFormatWithinLengthCap() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Agent-";
        final StubProvisioner provisioner = new StubProvisioner(name -> false);

        for (int i = 0; i < 500; i++) {
            final String name = provisioner.generateUniqueCharacterName(null);
            assertNotNull(name, "a free name must be produced");
            assertTrue(name.startsWith("Agent-"), name);
            assertTrue(name.length() <= DbAgentProvisioner.NAME_MAX_LENGTH,
                    "varchar(13) cap exceeded: " + name);

            final String rest = name.substring("Agent-".length());
            final String word = rest.replaceAll("\\d+$", "");
            assertTrue(DbAgentProvisioner.COMPANION_WORDS.contains(word),
                    "unexpected companion word in " + name);
            assertTrue(rest.matches("[A-Za-z0-9]{1,7}"), "alphanumeric word/suffix expected: " + name);
        }
    }

    @Test
    void retriesWithSuffixWhenTheBaseNameIsTaken() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Agent-";
        final AtomicInteger queries = new AtomicInteger();
        final StubProvisioner provisioner = new StubProvisioner(name -> queries.incrementAndGet() == 1);

        final String name = provisioner.generateUniqueCharacterName(null);

        assertNotNull(name, "the suffixed retry should succeed");
        assertTrue(name.startsWith("Agent-"), name);
        assertEquals(2, queries.get(), "one base probe plus one suffixed probe");
    }

    @Test
    void givesUpAfterBoundedAttempts() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Agent-";
        final StubProvisioner provisioner = new StubProvisioner(name -> true);

        assertNull(provisioner.generateUniqueCharacterName(null), "no free name must give up");
        assertTrue(provisioner.calls > 0 && provisioner.calls <= 20,
                "uniqueness probing must stay bounded: " + provisioner.calls);
    }

    @Test
    void usesConfiguredCompanionPrefix() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Pal-";
        final StubProvisioner provisioner = new StubProvisioner(name -> false);

        final String name = provisioner.generateUniqueCharacterName(null);
        assertNotNull(name);
        assertTrue(name.startsWith("Pal-"), name);
    }

    @Test
    void refusesAnOverlongCompanionPrefix() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Agent-1234567"; // 13 chars, no room
        final StubProvisioner provisioner = new StubProvisioner(name -> false);
        assertNull(provisioner.generateUniqueCharacterName(null));
    }

    @Test
    void companionPrefixDefaultsWhenMissingOrBlank() {
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = null;
        assertEquals("Agent-", AgentConfig.companionNamePrefix());
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "   ";
        assertEquals("Agent-", AgentConfig.companionNamePrefix());
    }

    @Test
    void spawnAcceptsEitherLabelOrCompanionPrefix() {
        YamlConfig.config.server.LLM_AGENT_NAME_PREFIX = "[AI]";
        YamlConfig.config.server.LLM_AGENT_COMPANION_NAME_PREFIX = "Agent-";

        assertTrue(AgentManager.hasAgentPrefix("[AI]Old"));
        assertTrue(AgentManager.hasAgentPrefix("Agent-Mochi"));
        assertFalse(AgentManager.hasAgentPrefix("RandomPlayer"));
        assertFalse(AgentManager.hasAgentPrefix(null));
    }
}
