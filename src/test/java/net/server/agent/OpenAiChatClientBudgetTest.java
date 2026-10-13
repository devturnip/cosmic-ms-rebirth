package net.server.agent;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatClientBudgetTest {

    private final int original = YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN;

    @AfterEach
    void restore() {
        YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = original;
        OpenAiChatClient.resetBudgetForTest();
    }

    @Test
    void capsRequestsWithinTheWindow() {
        OpenAiChatClient.resetBudgetForTest();
        YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = 2;

        assertTrue(OpenAiChatClient.tryConsumeCallBudget(), "first request within cap");
        assertTrue(OpenAiChatClient.tryConsumeCallBudget(), "second request within cap");
        assertFalse(OpenAiChatClient.tryConsumeCallBudget(), "third request exceeds the cap");
    }

    @Test
    void zeroMeansUnlimited() {
        OpenAiChatClient.resetBudgetForTest();
        YamlConfig.config.server.LLM_MAX_CALLS_PER_MIN = 0;

        for (int i = 0; i < 50; i++) {
            assertTrue(OpenAiChatClient.tryConsumeCallBudget(), "unlimited budget never rejects");
        }
    }
}
