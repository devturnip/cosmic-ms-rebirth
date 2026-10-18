package net.server.agent.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON-RPC/MCP protocol-level tests. The service-backed paths are exercised only for their
 * error handling here (the harness is disabled in test config), while protocol plumbing
 * (initialize, tools/list, unknown methods, bad payloads) is verified directly.
 */
class TestHarnessMcpServerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode response(String request) throws Exception {
        String raw = TestHarnessMcpServer.handleJson(request);
        assertNotNull(raw);
        return mapper.readTree(raw);
    }

    @Test
    void initializeReturnsProtocolVersionAndCapabilities() throws Exception {
        JsonNode out = response("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """);
        assertEquals("2.0", out.get("jsonrpc").asText());
        assertEquals("2024-11-05", out.path("result").path("protocolVersion").asText());
        assertTrue(out.path("result").path("capabilities").path("tools").isObject());
        assertEquals("cosmic-test-harness", out.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void initializedNotificationProducesNoResponse() {
        assertNull(TestHarnessMcpServer.handleJson(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void toolsListDescribesAllHarnessTools() throws Exception {
        JsonNode out = response("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = out.path("result").path("tools");
        assertEquals(7, tools.size());
        var names = new java.util.HashSet<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.containsAll(java.util.Set.of(
                "session_start", "session_end", "session_list", "observe", "drain_events", "act", "setup")));
        // every tool carries a JSON-schema input description
        tools.forEach(t -> assertEquals("object", t.path("inputSchema").path("type").asText()));
    }

    @Test
    void unknownMethodReturnsJsonRpcError() throws Exception {
        JsonNode out = response("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}");
        assertEquals(-32601, out.path("error").path("code").asInt());
    }

    @Test
    void unknownToolReturnsJsonRpcError() throws Exception {
        JsonNode out = response("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"fly"}}""");
        assertEquals(-32602, out.path("error").path("code").asInt());
    }

    @Test
    void malformedJsonReturnsParseError() throws Exception {
        JsonNode out = response("{not json");
        assertEquals(-32700, out.path("error").path("code").asInt());
    }

    @Test
    void disabledHarnessSurfacesToolError() throws Exception {
        // USE_TEST_HARNESS is false in the committed config.yaml used by the test run.
        JsonNode out = response("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"session_list","arguments":{}}}
                """);
        assertTrue(out.path("result").path("isError").asBoolean());
        assertTrue(out.path("result").path("content").get(0).path("text").asText()
                .contains("USE_TEST_HARNESS"));
    }
}
