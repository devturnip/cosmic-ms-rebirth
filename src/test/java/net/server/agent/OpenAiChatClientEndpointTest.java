package net.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiChatClientEndpointTest {

    @Test
    void appendsTheChatPathToABareBaseUrl() {
        assertEquals("https://api.deepseek.com/chat/completions",
                OpenAiChatClient.resolveEndpoint("https://api.deepseek.com"));
    }

    @Test
    void appendsTheChatPathToAVersionBaseUrl() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiChatClient.resolveEndpoint("https://api.deepseek.com/v1"));
        assertEquals("https://example.com/v1beta/chat/completions",
                OpenAiChatClient.resolveEndpoint("https://example.com/v1beta"));
    }

    @Test
    void keepsAFullChatCompletionsUrlUnchanged() {
        assertEquals("https://api.deepseek.com/chat/completions",
                OpenAiChatClient.resolveEndpoint("https://api.deepseek.com/chat/completions"));
    }

    @Test
    void keepsCustomEndpointsUnchanged() {
        String azure = "https://host.openai.azure.com/openai/deployments/gpt/chat/completions?api-version=2024-02-01";
        assertEquals(azure, OpenAiChatClient.resolveEndpoint(azure));
        assertEquals("http://localhost:11434/api/chat",
                OpenAiChatClient.resolveEndpoint("http://localhost:11434/api/chat"));
    }

    @Test
    void ignoresTrailingSlashes() {
        assertEquals("https://api.deepseek.com/chat/completions",
                OpenAiChatClient.resolveEndpoint("https://api.deepseek.com/"));
    }

    @Test
    void keepsBlankValuesBlank() {
        assertEquals("", OpenAiChatClient.resolveEndpoint(""));
        assertEquals("", OpenAiChatClient.resolveEndpoint(null));
        assertEquals("", OpenAiChatClient.resolveEndpoint("   "));
    }
}
