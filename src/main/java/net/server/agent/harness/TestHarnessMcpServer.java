package net.server.agent.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.server.agent.AgentAction;
import net.server.agent.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal MCP (Model Context Protocol) server exposing the test harness to an external LLM
 * agent. Implements the JSON-RPC 2.0 surface the protocol needs ({@code initialize},
 * {@code tools/list}, {@code tools/call}, {@code ping}) over two transports:
 * <ul>
 *     <li><b>stdio</b> — newline-delimited JSON on stdin/stdout, for agents embedded on the
 *     same host ({@code java -jar ... --test-harness-stdio}).</li>
 *     <li><b>http</b> — Streamable HTTP ({@code POST /mcp}, JSON responses), for agents
 *     driving a docker-compose server remotely.</li>
 * </ul>
 * Hand-rolled on Jackson rather than pulling in the MCP Java SDK + a servlet container; the
 * harness only ever returns non-streaming JSON, which keeps both transports ~trivial.
 */
public final class TestHarnessMcpServer {
    private static final Logger log = LoggerFactory.getLogger(TestHarnessMcpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "cosmic-test-harness";
    private static final String SERVER_VERSION = "1.0.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestHarnessMcpServer() {
    }

    /** Entry point from {@code Server#main}: starts the configured transport if enabled. */
    public static void bootstrap(String[] args) {
        if (!TestHarnessConfig.enabled()) {
            return;
        }
        boolean stdioRequested = args != null && List.of(args).contains("--test-harness-stdio");
        try {
            if (stdioRequested || "stdio".equals(TestHarnessConfig.transport())) {
                startStdio();
            } else {
                startHttp(TestHarnessConfig.httpPort());
            }
        } catch (Exception e) {
            log.error("Failed to start test-harness MCP server", e);
        }
    }

    public static void startStdio() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                log.info("Test-harness MCP server listening on stdio");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String response = handleJson(line);
                    if (response != null) {
                        System.out.println(response);
                        System.out.flush();
                    }
                }
            } catch (Exception e) {
                log.error("stdio MCP transport terminated", e);
            }
        }, "test-harness-stdio");
        thread.setDaemon(true);
        thread.start();
    }

    public static void startHttp(int port) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/mcp", exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response = handleJson(body);
                byte[] payload = response == null ? "{}".getBytes(StandardCharsets.UTF_8)
                        : response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            } catch (Exception e) {
                log.warn("HTTP MCP request failed", e);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        // JSON-RPC calls are independent; the default single-threaded executor would serialize
        // observe/act calls behind slow ones.
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("Test-harness MCP server listening on http://0.0.0.0:{}/mcp", port);
    }

    /**
     * Handles one JSON-RPC request. Returns the response JSON, or {@code null} for
     * notifications (no id) and unparseable input.
     */
    public static String handleJson(String request) {
        try {
            JsonNode root = MAPPER.readTree(request);
            if (root == null || !root.isObject() || !root.has("method")) {
                return MAPPER.writeValueAsString(error(null, -32600, "Invalid request: missing method"));
            }
            JsonNode id = root.get("id");
            boolean notification = id == null || id.isNull();
            String method = root.get("method").asText();
            ObjectNode response = switch (method) {
                case "initialize" -> result(id, initializeResult());
                case "notifications/initialized", "initialized" -> null;
                case "ping" -> result(id, MAPPER.createObjectNode());
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> callTool(id, params(root));
                default -> notification ? null
                        : error(id, -32601, "Method not found: " + method);
            };
            return response == null ? null : MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("MCP request failed to parse", e);
            try {
                return MAPPER.writeValueAsString(error(null, -32700, "Parse error: " + e.getMessage()));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static JsonNode params(JsonNode root) {
        return root.get("params");
    }

    private static ObjectNode initializeResult() {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = out.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode info = out.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return out;
    }

    private static ObjectNode callTool(JsonNode id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return error(id, -32602, "tools/call requires 'name'");
        }
        String name = params.get("name").asText();
        JsonNode args = params.get("arguments");
        try {
            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode content = out.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "text");
            switch (name) {
                case "session_start" -> text.put("text", MAPPER.writeValueAsString(
                        TestHarnessService.getInstance().startSession(
                                intArg(args, "jobId", 0), intArg(args, "level", 1),
                                intArg(args, "mapId", 0), intArg(args, "world", 0))));
                case "session_end" -> text.put("text", MAPPER.writeValueAsString(
                        TestHarnessService.getInstance().endSession(intArg(args, "sessionId"))));
                case "session_list" -> text.put("text", MAPPER.writeValueAsString(
                        TestHarnessService.getInstance().listSessions()));
                case "observe" -> {
                    boolean omni = args != null && args.has("omniscient") && args.get("omniscient").asBoolean();
                    text.put("text", MAPPER.writeValueAsString(
                            TestHarnessService.getInstance().observe(intArg(args, "sessionId"), omni)));
                }
                case "drain_events" -> text.put("text", MAPPER.writeValueAsString(
                        TestHarnessService.getInstance().drainEvents(intArg(args, "sessionId"))));
                case "act" -> {
                    int sessionId = intArg(args, "sessionId");
                    List<AgentAction> actions = HarnessActions.parse(args.get("actions"));
                    text.put("text", MAPPER.writeValueAsString(
                            TestHarnessService.getInstance().act(sessionId, actions)));
                }
                case "setup" -> {
                    int sessionId = intArg(args, "sessionId");
                    String op = args.get("op").asText();
                    AgentSession session = TestHarnessService.getInstance().getSession(sessionId);
                    Map<String, Object> opArgs = new java.util.LinkedHashMap<>();
                    if (args != null && args.has("args") && args.get("args").isObject()) {
                        args.get("args").fields().forEachRemaining(e ->
                                opArgs.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class)));
                    }
                    text.put("text", MAPPER.writeValueAsString(TestHarnessService.getInstance().setup(op, session, opArgs)));
                }
            default -> {
                return error(id, -32602, "Unknown tool: " + name);
            }
            }
            return result(id, out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return toolError(id, e.getMessage());
        } catch (Exception e) {
            log.warn("Tool {} failed", name, e);
            return toolError(id, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int intArg(JsonNode args, String key, int fallback) {
        if (args == null || !args.has(key)) {
            return fallback;
        }
        JsonNode value = args.get(key);
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("argument '" + key + "' must be an integer");
        }
        return value.asInt();
    }

    private static int intArg(JsonNode args, String key) {
        if (args == null || !args.has(key) || !args.get(key).canConvertToInt()) {
            throw new IllegalArgumentException("argument '" + key + "' is required and must be an integer");
        }
        return args.get(key).asInt();
    }

    private static ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id == null ? MAPPER.nullNode() : id);
        out.set("result", payload);
        return out;
    }

    private static ObjectNode toolError(JsonNode id, String message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode result = out.putObject("result");
        result.put("isError", true);
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message == null ? "unknown error" : message);
        return out;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return out;
    }

    private static ObjectNode toolsList() {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode tools = out.putArray("tools");
        tools.add(tool("session_start", "Provision an ephemeral test character and spawn it as a controllable session. Returns the full observation.",
                schema()
                        .put("jobId", "int (optional, 0 = Beginner)").put("level", "int (1-200, default 1)")
                        .put("mapId", "int (optional, 0 = job default)").put("world", "int (optional, default 0)")));
        tools.add(tool("session_end", "End a harness session: despawn and delete its character and account.",
                schema().put("sessionId", "int (required)")));
        tools.add(tool("session_list", "List live harness session ids.", schema()));
        tools.add(tool("observe", "Full structured observation of a session (position, hp/mp, monsters, items, npcs, players, inventory, equipment, quests, mesos, events).",
                schema().put("sessionId", "int (required)")));
        tools.add(tool("drain_events", "Drain buffered session events (map chat, party invites, map changes).",
                schema().put("sessionId", "int (required)")));
        tools.add(tool("act", "Submit player actions through the REAL packet handlers (server-validated). Blocks until applied; returns per-action {dispatched, accepted}. Actions: Say{message}, PartySay{message}, Whisper{targetName,message}, InviteToParty{targetName}, AcceptPartyInvite{partyId}, DenyPartyInvite{inviterName}, LeaveParty, MoveTo{x,y}, Follow{targetName}, StopFollowing, PickupItem{objectId}, UseItem{itemId}, Respawn, Attack{targetObjectId}, SkillAttack{targetObjectId,skillId}, SkillAttackMany{targetObjectIds[],skillId}, RechargeAmmo, CastBuff{skillId}, CastHeal{skillId}, NpcTalk{npcId}, NpcSelect{selection}, NpcRespondText{text}, NpcEndDialog, QuestStart{questId,npcId}, QuestComplete{questId,npcId}, QuestForfeit{questId}, AddBuddy{name}, EnterCashShop, CashBuy{useNX,snCS}, TradeInvite{targetName}, TradeAccept, TradePutItem{inventoryType,slot,quantity,targetSlot}, TradePutMesos{amount}, TradeConfirm, Remember{fact}, Idle. NPC flow: NpcTalk opens a dialog, then observe npcDialog{type,text} and respond with NpcSelect/NpcRespondText/NpcEndDialog. Quests auto-detect scripted vs WZ-only quests (script dialogs appear via npcDialog; both require the quest NPC nearby). QuestForfeit abandons a started quest. Trade flow between two sessions: TradeInvite{targetName} -> invitee TradeAccept -> both TradePutItem/TradePutMesos -> both TradeConfirm (observe trade{items,mesos} for staged state). CashBuy useNX: 1=NX credit, 2=maple point, 4=prepaid; grant_cash setup op funds NX. After EnterCashShop the character is off the map (end the session when done).",
                schema().put("sessionId", "int (required)").put("actions", "array (required)")));
        tools.add(tool("setup", "Cheat ops for scenario setup (gated by USE_TEST_HARNESS): teleport{mapId,portal?}, spawn_mob{mobId,x,y}, kill_mob{objectId,withDrops?}, list_mobs, grant_item{itemId,quantity?}, grant_mesos{amount}, set_level{level}, set_quest_state{questId,state}, spawn_drop{itemId,quantity?,x,y}, heal.",
                schema().put("sessionId", "int (required)").put("op", "string (required)").put("args", "object (op-specific)")));
        return out;
    }

    private static ObjectNode tool(String name, String description, ObjectNode hints) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        hints.fieldNames().forEachRemaining(field -> {
            ObjectNode prop = props.putObject(field);
            prop.put("type", field.equals("actions") || field.equals("args") ? "array" : "string");
            prop.put("description", hints.get(field).asText());
        });
        return tool;
    }

    private static ObjectNode schema() {
        return MAPPER.createObjectNode();
    }
}
