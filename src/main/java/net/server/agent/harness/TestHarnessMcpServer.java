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
                                intArg(args, "mapId", 0), intArg(args, "world", 0), intArg(args, "channel", 1))));
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
        tools.add(tool("session_start", "Provision an ephemeral test character and spawn it as a controllable session. Returns the full observation (including sessionId and channel). Match the channel to a real client's channel when testing cross-visibility.",
                schema()
                        .put("jobId", "int (optional, 0 = Beginner)").put("level", "int (1-200, default 1)")
                        .put("mapId", "int (optional, 0 = job default)").put("world", "int (optional, default 0)")
                        .put("channel", "int (optional, default 1)")));
        tools.add(tool("session_end", "End a harness session: despawn and delete its character and account.",
                schema().put("sessionId", "int (required)")));
        tools.add(tool("session_list", "List live harness session ids.", schema())) ;
        tools.add(tool("observe", "Full structured observation of a session (channel, position, hp/mp, monsters, items, npcs, players, inventory, equipment, quests, buddies, trade, cashShop, storage, npcDialog, interaction, guild, alliance, marriage, family, mesos, events).",
                schema().put("sessionId", "int (required)").put("omniscient", "boolean (optional: whole-map perception)")));
        tools.add(tool("drain_events", "Drain buffered session events (map chat, party invites, map changes).",
                schema().put("sessionId", "int (required)")));
        tools.add(tool("act", "Submit player actions through the REAL packet handlers (server-validated). Blocks until applied; returns per-action {dispatched, accepted}. Actions: Say{message}, PartySay{message}, Whisper{targetName,message}, InviteToParty{targetName}, AcceptPartyInvite{partyId}, DenyPartyInvite{inviterName}, LeaveParty, MoveTo{x,y}, Follow{targetName}, StopFollowing, PickupItem{objectId}, UseItem{itemId}, Respawn, Attack{targetObjectId}, SkillAttack{targetObjectId,skillId}, SkillAttackMany{targetObjectIds[],skillId}, RechargeAmmo, CastBuff{skillId}, CastHeal{skillId}, NpcTalk{npcId}, NpcSelect{selection}, NpcRespondText{text}, NpcEndDialog, QuestStart{questId,npcId}, QuestComplete{questId,npcId}, QuestForfeit{questId}, AddBuddy{name}, EnterCashShop, CashBuy{useNX,snCS}, TradeInvite{targetName}, TradeAccept, TradePutItem{inventoryType,slot,quantity,targetSlot}, TradePutMesos{amount}, TradeConfirm, EnterPortal{portalName} (scripted portals via the real CHANGE_MAP_SPECIAL packet, e.g. PQ stage transitions; portalName is the portal NAME like next00/st00, NOT the portal script name like kpq1), Remember{fact}, Idle. NPC flow: NpcTalk opens a dialog, then observe npcDialog{type,text} and respond with NpcSelect/NpcRespondText/NpcEndDialog. Quests auto-detect scripted vs WZ-only quests (script dialogs appear via npcDialog; both require the quest NPC nearby). QuestForfeit abandons a started quest. Trade flow between two sessions: TradeInvite{targetName} -> invitee TradeAccept -> both TradePutItem/TradePutMesos -> both TradeConfirm (observe trade{items,mesos} for staged state). CashBuy useNX: 1=NX credit, 2=maple point, 4=prepaid; grant_cash setup op funds NX. After EnterCashShop the character is off the map (end the session when done). PlayerInteraction rooms: MiniGameCreate{gameType:'omok'|'matchcard',description?,password?,pieceType} (needs the matching item: omok board 4080000+pieceType / match cards 4080100 via setup grant_item), RoomVisit{ownerName,password?} (join a room on the map by owner name), MiniGameStart (owner), MiniGameMoveOmok{x,y,piece}, MiniGameSelectCard{turn,slot} (turn 1=first flip, 0=second), MiniGameGiveUp, MiniGameTieRequest, MiniGameTieAnswer{accept}, RoomExit (leave any room); room state (board, scores, merchant listings) is in the observation's `interaction` block. Hired-merchant flow (owner): teleport to a free-market room (910000001+), setup grant_item{503xxxx}, HiredMerchantCreate{description,itemId} -> HiredMerchantPutItem{inventoryType,slot,bundles,perBundle,price} -> HiredMerchantOpen; visitors RoomVisit{ownerName} then HiredMerchantBuy{itemIndex,quantity}; the owner re-enters via RoomVisit{ownerName:<self>} then HiredMerchantWithdrawMesos / HiredMerchantTakeItemBack{slot} / HiredMerchantClose. Guild/alliance/marriage/family: GuildCreate{name} (needs CREATE_GUILD_MIN_PARTNERS guild-less party-free co-founders on the leader's map + CREATE_GUILD_COST mesos; starts a match-checker confirmation) -> each co-founder GuildCreateAnswer{accept:true} (the last acceptance founds the guild at Guild HQ 200000301); GuildInvite{targetName} -> invitee GuildAcceptInvite{inviterName}; GuildLeave{name}, GuildExpel{targetId,targetName} (rank 1/2), GuildChangeRankTitle{ranks[5]} (rank 1), GuildChangeRank{targetId,newRank}, GuildNotice{notice}; guild state is in the observation's `guild` block (name, rank, members, notice). Alliance: guild masters create a union via the Lenario NPC 2010009 at Guild HQ (NpcTalk + NpcSelect + NpcRespondText for the name); AllianceInvite{guildName} (target guild's master gets the invite) -> invitee's master AllianceAcceptInvite{inviterName}; AllianceLeave; `alliance` observation block. Marriage: proposer setup grant_item{itemId:2240000..2240003} + set_level 50 + (target) set_gender opposite -> Engage{targetName,itemId} -> target EngageAnswer{accept,proposerName} (both gain the empty engagement box in ETC; partnerId set on both); BreakEngagement{itemId} drops the token to sever (also divorces with a 1112803x wedding ring); state in `marriage`. Family: FamilyAdd{name} (same map, levels within 20, target >10) -> target FamilyAccept{accept,inviterName}; state in `family`.",
                schema().put("sessionId", "int (required)").put("actions", "array (required)")));
        tools.add(tool("setup", "Cheat ops for scenario setup (gated by USE_TEST_HARNESS): teleport{mapId,portal?,x?,y?} (x+y place the character at an exact position on the target map, e.g. PQ rect placements), spawn_mob{mobId,x,y}, kill_mob{objectId,withDrops?}, list_mobs, grant_item{itemId,quantity?}, grant_mesos{amount}, grant_cash{amount,type?}, set_level{level}, set_quest_state{questId,state}, spawn_drop{itemId,quantity?,x,y}, heal, set_gender{gender} (0=male/1=female — arrange opposite-gender engagement pairs), debug_players{mapId?} (diagnostic: who the server thinks is on the map/channel/world).",
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
            String hint = hints.get(field).asText();
            String type = switch (field) {
                // payloads the harness parses as JSON structures
                case "actions", "args" -> "array".equals(hint) || hint.startsWith("object") ? "object" : "array";
                // booleans
                case "omniscient", "withDrops", "quick" -> "boolean";
                // everything else the harness reads numerically or as text
                default -> hint.contains("int") || field.endsWith("Id") || field.equals("sessionId")
                        || field.equals("level") || field.equals("x") || field.equals("y")
                        || field.equals("slot") || field.equals("quantity") || field.equals("amount")
                        || field.equals("world") || field.equals("mapId") || field.equals("jobId")
                        || field.equals("npcId") || field.equals("questId") || field.equals("mobId")
                        || field.equals("objectId") || field.equals("skillId") || field.equals("partyId")
                        || field.equals("mesos") || field.equals("targetSlot") || field.equals("operation")
                        || field.equals("useNX") || field.equals("snCS") || field.equals("channel")
                        ? "integer" : "string";
            };
            prop.put("type", type);
            prop.put("description", hint);
        });
        return tool;
    }

    private static ObjectNode schema() {
        return MAPPER.createObjectNode();
    }
}
