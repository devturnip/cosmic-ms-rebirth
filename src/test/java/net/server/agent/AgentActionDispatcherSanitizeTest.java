package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActionDispatcherSanitizeTest {

    private final AgentActionDispatcher dispatcher = new AgentActionDispatcher();

    @Test
    void nullReturnsNull() {
        assertNull(dispatcher.sanitize(null));
    }

    @Test
    void blankReturnsNull() {
        assertNull(dispatcher.sanitize("   "));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals("hello", dispatcher.sanitize("  hello  "));
    }

    @Test
    void leadingSlashCommandIsStripped() {
        String result = dispatcher.sanitize("/warp");

        assertNotNull(result);
        assertFalse(result.startsWith("/"));
    }

    @Test
    void allCommandHeadingsAreStripped() {
        // '@' and '!' are command headings recognised by CommandsExecutor/GeneralChatHandler.
        assertEquals("item 1000", dispatcher.sanitize("@item 1000"));
        assertEquals("warp 100000000", dispatcher.sanitize("!warp 100000000"));
        assertEquals("hello", dispatcher.sanitize("@!hello"));
    }

    @Test
    void longMessageIsClampedToMaxLength() {
        String result = dispatcher.sanitize("a".repeat(200));

        assertNotNull(result);
        assertTrue(result.length() <= 127);
    }
}
