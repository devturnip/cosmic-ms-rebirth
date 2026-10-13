# LLM Agent Players — Implementation Plan

> Status: IMPLEMENTED (M0–M2 done, M3 partial). See the implementation-status section below.
> Scope: let LLM-driven agents join the server as ordinary players so that solo
> players can form parties and chat with them.
>
> **New here? Read `docs/llm-agent-players-handoff.md` first** — it is the practical
> developer handoff (file map, invariants, recipes, build/run, review history).

---

## Locked decisions

Agreed with the requester:

| # | Decision |
| --- | --- |
| Q1 Brain placement | **In-process Java** `AgentBrain` calling the LLM with Java 21 `java.net.http.HttpClient` (no new Maven dependency). |
| Q2 Provider | **OpenAI-compatible** chat-completions endpoint + tool calling (works with OpenAI/Azure/local Ollama/llama.cpp/vLLM). URL/key/model from config. |
| Q3 Scope | **M1 + M2 + M3** in scope (chat, party, presence/combat assist). M4 (proactive matchmaking) deferred. |
| Q4 Memory | **Persist per-agent memory** so agents have enduring character/personality across sessions. |
| Q5 Perception | **Bounded range snapshot** (Option C) as default, with an `OMNISCIENT` dev toggle. See §5.4. |
| Q6 Autonomy | **Strictly reactive** for now (reply to chat, respond to invites). No proactive inviting/patrolling. |
| Q7 Validation | **Route all actions through the real packet handlers** via synthesized `InPacket`s. |
| Q8 Labeling/safety | Agents are **visibly labeled** (naming convention), plus content filter, rate limits, and a global kill switch. |

---

## Implementation status (branch `feature/llm-agent-players`)

Delivered:

- [x] `Client` outbound seam (`writePacket`) + null-safe channel ops + `getPacketProcessor()`.
- [x] `PlayerLoginService.finishLogin` extracted from `PlayerLoggedinHandler` (behavior-preserving).
- [x] `AgentClient`, `AgentSession`, `AgentManager`, `AgentPerception`, `AgentActionDispatcher`.
- [x] `AgentEvent` / `AgentAction` / `AgentBrain` / `ScriptedAgentBrain` / `AgentMemory`.
- [x] OpenAI-compatible LLM brain (`OpenAiChatClient`, `LlmAgentBrain`, `AgentPersona`) with
  tool calling and fail-safe fallback to the scripted brain.
- [x] Persistent memory (`AgentMemoryStore` + `agent_memory` table, migration `v0.15.0.sql`).
- [x] Chat/party/whisper/party-invite hooks route events to agents.
- [x] `/agent spawn|despawn|list|say|follow|stopfollow|search` GM command (rank 5).
- [x] Config keys in `ServerConfig` + `config.yaml`; `AgentManager` start/stop in `Server`.
- [x] Unit tests: `ScriptedAgentBrain`, `AgentMemory`, `AgentConfig`, dispatcher sanitize,
  movement packet encoding, persona store (JSON/YAML), loot behaviour.
- [x] M2: party-search registration (`/agent search`), follow-the-leader (auto + `/agent follow`),
  party-update and map-change perception events, and cross-map follow via warp
  (`LLM_AGENT_FOLLOW_WARP`).
- [x] M3 (substantially): monster/item/NPC perception, clamped synthetic movement, item
  pickup, potion self-sustain, respawn, buff-skill casting, melee/magic/ranged basic +
  skill attacks (auto modes behind config), heal-skill casting, auto-looting kills,
  multi-target skill attacks (incl. the LLM `attack_many` tool), multi-line skill attacks
  (`max(bulletCount, attackCount)` damage lines), and ammo perception + shop recharge for
  throwing stars/bullets.
- [x] Hardening from review: strip all command headings (`/`, `@`, `!`) from agent chat;
  refuse to spawn a character/account that is already online; synchronous despawn;
  spawn off the Netty event loop; content filter defaults on; clamped movement;
  concurrency-safe rate limiter; pickup range/ownership validation; post-mutation follow;
  packet-exact attack/charge/hurricane/poison layouts; ammo type/level/bulletCount gating;
  non-damaging/buff/fixed-damage skill rejection; atomic account claim on spawn
  (`registerAgentClientIfAbsent`) to avoid kicking a real player; `StatEffect#hasDamage()` so
  utility skills are never attacked with; loot ownership matching `Character#pickupItem`;
  and `mapTransitioning` cleared for agents at spawn and after each warp.

Not yet implemented (M2 remainder + M3 remainder):

- [ ] Combat depth: arrow purchase for bow/crossbow (arrows are not rechargeable), local
  cooldown tracking, MP/HP-cost timing on the skill path, Hurricane/Rapid Fire after-delay
  semantics, monster facing, flee/aggro management, cast animation (multi-target, multi-line,
  heal and star/bullet recharge are done).
- [ ] Real pathing (portals/footholds); movement is a clamped straight-line step. Cross-map
  follow warps directly (party-warp style) rather than walking to a portal.
- [ ] Channel-follow (cross-channel party follow needs a channel transfer).
- [ ] LLM-driven movement policy beyond `move_to`/follow (heal has an LLM tool, but there is
  no LLM tool for multi-target attacks yet).
- [ ] Proactive behaviour (M4 — deferred; note `/agent search` does enable party-search
  auto-invites, which is a form of matchmaking).
- [ ] Player-facing opt-out / bot notice beyond the name prefix.

---

## 1. Goal

Allow one or more LLM-driven **agents** to exist on the server as normal
`Character`s that:

- appear in a map exactly like a real player (spawn/despawn/move/chat animations),
- can be invited to / join / leave a **party** and participate in party chat,
- can hold a conversation with nearby players (general, party, whisper),
- can (later phases) follow, fight, buff, loot, and warp with their party.

The intended user story: a solo player logs in, meets an agent in a town or via
party search, chats with it, parties up, and plays together.

---

## 2. Non-goals (for this effort)

- Changing the v83 game client. Agents live **server-side only**; real clients
  need no patches.
- Building a full external client protocol emulator (see §4, Option B).
- Making agents indistinguishable from humans for anti-cheat purposes. They are
  explicitly server-owned entities and should be flagged internally.
- Long-term game balance / progression design. Agents are social/party
  companions first.

> Note: the repo README lists "custom gameplay features" as a non-goal for the
> upstream project. This is inherently a custom feature; it is proposed as an
> opt-in, config-gated addition (default off) so it does not disturb vanilla
> behavior.

---

## 3. Codebase primer (what matters here)

Relevant existing machinery:

| Concern | Where |
| --- | --- |
| Netty listener / pipeline / encryption | `net/netty/ServerChannelInitializer.java`, `LoginServerInitializer`, `ChannelServerInitializer` |
| Per-connection session | `client/Client.java` (extends `ChannelInboundHandlerAdapter`; `sendPacket` → `ioChannel.writeAndFlush`) |
| Packet routing | `net/PacketProcessor.java` (`getChannelServerProcessor(world, channel)`, `getHandler(opcode)`) |
| Inbound opcodes | `net/opcodes/RecvOpcode.java` (`GENERAL_CHAT 0x31`, `MULTI_CHAT 0x77`, `WHISPER 0x78`, `PARTY_OPERATION 0x7C`, `DENY_PARTY_REQUEST 0x7D`, `MOVE_PLAYER 0x29`, `PLAYER_LOGGEDIN 0x14`) |
| Chat | `net/server/channel/handlers/GeneralChatHandler.java`, `MultiChatHandler.java` |
| Party | `net/server/channel/handlers/PartyOperationHandler.java`, `DenyPartyRequestHandler.java`, `net/server/world/Party.java`, `World.java`, `net/server/coordinator/world/InviteCoordinator.java` |
| Party matchmaking | `net/server/coordinator/partysearch/PartySearchCoordinator.java` + `PartySearch*Handler` |
| Player model | `client/Character.java` (`loadCharFromDB`, `newClient`, `sendPacket` → `client.sendPacket`) |
| Login/bootstrap sequence | `net/server/channel/handlers/PlayerLoggedinHandler.java` |
| Map broadcast / spawn | `server/maps/MapleMap.java` (`addPlayer`, `broadcastMessage`, `broadcastSpawnPlayerMapObjectMessage`) |
| Storage / registry | `net/server/PlayerStorage.java`, `net/server/channel/Channel.java`, `net/server/world/World.java` |
| GM commands | `client/command/CommandsExecutor.java` + `client/command/commands/gmN/` |
| Async work | `server/ThreadManager.java`, `server/TimerManager.java` |
| Scripting (GraalVM JS) | `scripting/AbstractScriptManager.java` |
| Test harness | `src/test/java/testutil/HandlerTest.java`, `Packets.java`, `Mocks.java`; `net/packet/ByteBufInPacket` |

Key facts that shape the design:

1. `Client.sendPacket` is `public` and non-final → an agent client can override
   the outbound path instead of writing to a socket.
2. `Character.sendPacket` delegates to `client.sendPacket`, and map/world/party
   code always reaches players through `Character`/`Client`, so intercepting the
   agent's `Client` captures everything the agent would "see" (if we choose to).
3. Inbound actions are just `(opcode, InPacket)` dispatched through
   `PacketHandler.handlePacket(packet, client)` — the existing tests already
   build `InPacket`s in memory (`testutil/Packets`). An agent can reuse **all**
   real handlers by synthesizing inbound packets.
4. `Client.createMock()` already exists (used by `PlayerNPC`,
   `SessionCoordinator`) but has `ioChannel == null`, so its `sendPacket` would
   NPE. This is the seam we extend.
5. There is **no HTTP client dependency**, but Java 21 ships
   `java.net.http.HttpClient`, so no new Maven dependency is strictly required
   to call a remote LLM.

---

## 4. Design options

### Option A — External client emulator (real TCP bot)
Reimplement the v83 handshake, `PacketCodec` encryption (AES/Shanda), login
flow, and every outbound packet decode; run an external process per agent.

- ➕ Zero changes to server internals; agents are truly "remote".
- ➖ Enormous, brittle effort (crypto + protocol + login + movement parsing).
- ➖ Duplicates the entire server logic in another language.
- **Rejected.**

### Option B — In-process headless agent, direct API calls
Create a special `Client` inside the JVM, load a real `Character`, register it in
`Channel`/`World`/`MapleMap`, and drive it by calling server methods directly
(e.g. `world.partyChat(...)`, `map.broadcastMessage(...)`).

- ➕ No protocol work, no crypto, full access.
- ➖ Bypasses handler validation (easy to create illegal states).
- ➖ Diverges from the real code paths over time.
- **Partially usable, but not preferred as the primary mechanism.**

### Option C — In-process agent that reuses the real packet pipeline (**recommended**)
Agent is an in-process `AgentClient extends Client`:

- **Outbound ("perception")**: override the write path so packets are captured,
  optionally decoded, and forwarded to the event bus / transcript.
- **Inbound ("actions")**: build synthetic `InPacket`s and dispatch them through
  the **normal `PacketProcessor` handlers** exactly as a client would
  (`GENERAL_CHAT`, `MULTI_CHAT`, `PARTY_OPERATION`, `MOVE_PLAYER`, attack, etc.).
- **Bootstrap**: reuse the real login/init flow via a newly extracted service
  (see M0), so the agent passes through the same state machine.

- ➕ Maximum code reuse; actions go through real validation.
- ➕ No crypto/protocol work; no client changes.
- ➕ Easy to test with existing `testutil` helpers.
- ➖ Requires a small refactor of `Client`'s socket write path and of the
  `PlayerLoggedinHandler` bootstrap sequence.

**Recommended: Option C.** The remainder of this plan assumes it.

---

## 5. Recommended architecture

```
                      ┌─────────────────────────────────────────────┐
                      │                 AgentManager                │
                      │  registry, lifecycle, scheduler, config     │
                      └───────────────┬─────────────────────────────┘
                                      │ owns
                     ┌────────────────┴──────────────────┐
                     │            AgentSession           │
                     │  (one per agent, serialized actor)│
                     │  ┌────────────┐  ┌─────────────┐  │
                     │  │ AgentBrain │  │ Observation │  │
                     │  │ (LLM)      │◄─┤   buffer    │  │
                     │  └─────┬──────┘  └──────▲──────┘  │
                     │        │ actions        │ events  │
                     │  ┌─────▼──────┐  ┌──────┴──────┐  │
                     │  │  Action    │  │ AgentEvent  │  │
                     │  │ Dispatcher │  │    Bus      │  │
                     │  └─────┬──────┘  └──────▲──────┘  │
                     └────────┼────────────────┼─────────┘
                              │ synthetic      │ hooks in
                              │ InPackets      │ chat/party/map
                     ┌────────▼────────────────┴─────────┐
                     │  AgentClient extends Client        │
                     │  (in-process, no socket)           │
                     │  → real PacketProcessor handlers   │
                     │  → real Character / Channel / World│
                     └────────────────────────────────────┘
```

### 5.1 `AgentClient` (packet sink + session)
- New class, e.g. `client/AgentClient.java` (or `agent/AgentClient.java`).
- Extends `Client`. Needs a small, surgical change in `Client`:
  - extract the outbound write into an overridable hook, e.g.
    `protected void writePacket(Packet p)` used by `sendPacket` (and the other
    `ioChannel` uses), so `AgentClient` can capture instead of writing to a
    socket. Also make the few direct `ioChannel` uses (idle-check, close)
    null-safe.
  - add a `Client.createAgent(...)` factory alongside `createLoginClient` /
    `createChannelClient` / `createMock`.
- `AgentClient`:
  - holds a reference to its `AgentSession`,
  - overrides `writePacket` to record outbound packets (for transcript/debug and
    optional decode) and to push perception events,
  - never participates in the Netty pipeline.

### 5.2 `AgentManager` (lifecycle + registry)
- New package: `net/server/agent/` (or `agent/`).
- Responsibilities:
  - `spawn(agentId/spec)` / `despawn(agentId)`; keep `Map<String, AgentSession>`.
  - start/stop with `Server` (`Server.init()` / `shutdown()`).
  - own a bounded executor for LLM calls (`ThreadManager` or a dedicated pool),
    **never** the Netty event loop.
  - per-agent serial executor ("actor") so a session mutates game state from one
    thread at a time.
  - config parsing (see §5.9).

### 5.3 Agent bootstrap (login without a socket)
- Extract the ~300-line `PlayerLoggedinHandler.handlePacket` body into a
  reusable service, e.g. `net/server/agent/AgentLoginService` or
  `PlayerLoginService.login(Client, Character, channel, world)`.
  - This is the single largest refactor and the riskiest step; do it first and
    keep the handler behavior byte-for-byte identical (it can call the service).
- Agent login then: load/create `Character` from DB → attach `AgentClient` →
  run the extracted bootstrap → `channel.addPlayer`, `world.addPlayer`,
  `map.addPlayer` → agent is now a real on-map player.
- Skip login-server/PIC/HWID/multiclient gates deliberately (agent is trusted,
  in-process); record this as an explicit, config-gated bypass.

### 5.4 Perception (what the agent knows)
Two complementary sources; prefer explicit hooks over packet decoding:

1. **Explicit hooks** at a handful of choke points → typed events on an
   `AgentEventBus`:
   - map chat: `GeneralChatHandler` (and pet chat / chalkboard if desired),
   - party chat: `World.partyChat`,
   - whisper: whisper handler / `World` buddy path,
   - party invite: `InviteCoordinator.createInvite(PARTY, …)`,
   - party updates: `World.updateParty(...)`,
   - map change: `Character` map transition,
   - damage/death/buff events (for phase M3).
2. **State snapshots** built directly from server objects when the brain asks
   "what do I see?": nearby `Character`s, `Monster`s, `NPC`s, `MapItem`s,
   portals, own HP/MP/EXP/level/job/position, party roster (`PartyCharacter`),
   map id/name. This avoids decoding every outbound packet.

Outbound packet capture on `AgentClient` is still useful for a faithful
transcript and for debugging, but is **not** the primary perception channel.

**Perception mode (Q5, decided):** default is a **bounded range snapshot**: at
observation time, query `MapleMap.getMapObjectsInRange(agent.position,
rangeSq, types)` for players/monsters/NPCs/items/portals, plus the agent's own
stats and party roster. `getRangedDistance()` already equals `722500` (850²)
when `USE_MAXRANGE` is off, but the agent must use its **own** range constant so
fairness does not silently change with the broadcast config.

- `LLM_AGENT_PERCEPTION=BOUNDED` (default) — range-limited, fair-ish, no packet
decoding.
- `LLM_AGENT_PERCEPTION=OMNISCIENT` — whole-map state, for dev/debug only.

Chat is always delivered via explicit event hooks (§5.4 item 1), independent of
this mode, so conversation quality is unaffected. (Option B — decoding all
outbound packets — was rejected as too costly for the marginal fairness gain.)

### 5.5 Action model
An `AgentAction` enum/record set, dispatched by `AgentActionDispatcher`:

- Preferred path: build a synthetic `InPacket` for the real opcode and dispatch
  via `PacketProcessor.getChannelServerProcessor(world, channel)`:
  `SAY` → `GENERAL_CHAT`; `PARTY_SAY` → `MULTI_CHAT`; `PARTY_INVITE/JOIN/LEAVE`
  → `PARTY_OPERATION`; `MOVE` → `MOVE_PLAYER`; `ATTACK` → ranged/close-range
  damage opcodes; `ACCEPT/DENY_INVITE` → `PARTY_OPERATION`/`DENY_PARTY_REQUEST`.
- Fallback for things with no clean client opcode (e.g. accept party search,
  warp): call the same server-side method the handler calls.

This gives the agent the same validation, spam guards, and autoban hooks as a
real client (minus HWID/session gates).

### 5.6 `AgentBrain` (the LLM boundary)
- Interface, e.g.:
  ```java
  interface AgentBrain {
      // returns a list of actions given observation + persona + memory
      List<AgentAction> decide(AgentObservation obs, AgentContext ctx);
  }
  ```
- Implementations:
  - `LlmAgentBrain` — Java 21 `HttpClient`; OpenAI-compatible chat-completions
    with **tool/function calling** so actions map to a declared tool schema.
    Works with OpenAI, Azure, Anthropic-compatible proxies, and local servers
    exposing an OpenAI API (Ollama, llama.cpp, vLLM, LM Studio).
  - `ScriptedAgentBrain` — deterministic canned/dialogue-tree brain for tests
    and for zero-cost fallback.
  - (optional) `JsAgentBrain` — reuse the existing GraalVM JS scripting layer to
    prototype personas without recompiling.
- Persona/memory (Q4, decided — persisted): a small per-agent persona config
  (name, personality, party-friendly behavior) stored in `LLM_AGENT_PERSONAS_FILE`
  or DB, plus **persistent memory** keyed by the agent's character id:
  - short-term: bounded rolling window of recent dialogue/events (in memory),
  - long-term: durable facts (relationships, party history, opinions) persisted
    in a new `agent_memory` table (or `temp_data` keys if we want zero schema
    churn).
  - The brain receives a compact memory digest on each decision; the brain may
    emit a `remember` action to write durable facts.
  - Persistence must be debounced/async so LLM round-trips never block on DB I/O.

### 5.7 Party integration (core requirement)
- **Being invited:** hook `InviteCoordinator.createInvite(InviteType.PARTY, …)`;
  the agent's brain decides accept/deny; accept is applied by dispatching a
  synthetic `PARTY_OPERATION` join (op `3`) or by calling
  `Party.joinParty(player, partyid, false)` — choose after confirming the invite
  semantics.
- **Inviting:** dispatch `PARTY_OPERATION` invite (op `4`) for a chosen nearby
  player.
- **Party chat:** `MultiChatHandler` type `1` → `World.partyChat`.
- **Roster/HP:** `PartyCharacter`, `player.updatePartyMemberHP()`,
  `receivePartyMemberHP()` already exist; ensure the agent's bootstrap calls
  them (as `PlayerLoggedinHandler` does).
- **Matchmaking:** register agents with
  `PartySearchCoordinator.registerPartyLeader(...)` (and the party-search
  storage) so solo players using the in-game party search can find them.

### 5.8 Scheduler / concurrency
- Tick the brain on a fixed cadence (e.g. every 1–3 s) **and** react
  immediately to events, coalescing to at most one in-flight LLM call per agent.
- LLM call executes on the shared bounded pool; the response is applied on the
  agent's serial executor.
- Never block Netty event-loop threads. Cap concurrent LLM calls globally
  (config) and per agent.
- Map/Character mutations are already lock-protected in this codebase
  (`MapleMap`, `Party`, inventories), but keep per-agent serialization to bound
  races and make behavior deterministic.

### 5.9 Identity, provisioning, persistence
- Each agent needs a real `accounts` + `characters` row (party/chat/level all
  assume a real character id).
- Provisioning options: a startup/command-driven provisioner that inserts
  account+character, or a pool of pre-seeded agent accounts.
- Internal flag: add agents to `AgentManager` registry and persist a marker
  (new `accounts`/`characters` column or a `temp_data`/`agent_registry` key) so
  they survive restarts and so `/agent` commands target them reliably.
- Labeling (Q8, decided): the client cannot easily render a "bot" tag, so agents
  are **named distinguishably** (e.g. a configurable prefix/suffix such as
  `[AI]` or a name pool) so players can tell them apart. The same label is used
  when registering with party search.
- Memory storage: a dedicated `agent_memory` table (added via a new file under
  `database/sql/migration/`), keyed by character id; or `temp_data` keys if we
  want zero schema churn.

### 5.10 Safety, moderation, anti-cheat
- Bypass: multi-client/HWID/IP/PIC gates, idle-disconnect, autoban, and packet
  spam guards where they'd false-positive on a non-network client. Make the
  bypass explicit and only reachable for registered agents.
- Still enforce: map mute, chat length limits, and (recommended) party/level
  requirements — agents should not corrupt normal game invariants.
- LLM output: length clamp, profanity/content filter (configurable), and a
  global kill switch. Log every agent utterance and every action.
- Audit: `AgentAction` transcript + optional DB log table.
- Rate limits: per-agent messages/actions per second (separate from player
  spam guards).

### 5.11 Configuration (`config.yaml`, `server` section)
Proposed keys (all default off/neutral):
```yaml
USE_LLM_AGENTS: false
LLM_AGENTS_MAX: 4
LLM_AGENT_TICK_MS: 2000
LLM_AGENT_PERCEPTION: "BOUNDED"       # BOUNDED (default, fair-ish) | OMNISCIENT (dev only)
LLM_AGENT_VIEW_RANGE: 722500           # squared view radius used by BOUNDED
LLM_API_URL: ""
LLM_API_KEY: ""
LLM_MODEL: ""
LLM_MAX_CONCURRENT_CALLS: 4
LLM_AGENT_NAME_PREFIX: "[AI]"          # client-visible bot label
LLM_AGENT_CONTENT_FILTER: true
LLM_AGENT_PERSONAS_FILE: ""            # yaml/json of persona definitions
LLM_AGENT_MEMORY_PERSIST: true         # persist long-term memory per character
```

### 5.12 GM / admin commands
- `/agent spawn <persona> [mapid]`, `/agent despawn <name|id>`,
  `/agent list`, `/agent say <name> <msg>` (inject as agent chat),
  `/agent tell <name> <msg>` (out-of-band instruction to the brain),
  `/agent mute`, `/agent pause`.
- Follow the existing `CommandsExecutor` pattern (new `gm`-level command class).

---

## 6. Phased milestones

Each milestone is independently shippable behind `USE_LLM_AGENTS`.

### M0 — Feasibility spike ("puppet player", no LLM)
- Extract `PlayerLoggedinHandler` bootstrap into a reusable service.
- Add overridable write hook to `Client`; add `AgentClient` + `createAgent`.
- Add `AgentManager` skeleton, `AgentEventBus`, spigot `writePacket` capture.
- `/agent spawn` creates a real headless character in the current map.
- Verify from a real client: the agent appears, moves (synthetic `MOVE_PLAYER`),
  and chats (synthetic `GENERAL_CHAT`).
- **Exit criteria:** a scripted agent can be seen and heard by a normal client.

### M1 — Reactive chat agent
- `AgentBrain` interface + `LlmAgentBrain` (Java HttpClient, tool calling) +
  `ScriptedAgentBrain`.
- Perception: map chat, whisper, party chat events; state snapshot.
- Actions: general chat, party chat, whisper, emote; movement optional.
- Bounded scheduler + per-agent serialization; config; logging.
- **Exit criteria:** a solo player can hold a coherent conversation with an
  agent in a map, and `/agent tell` can steer it.

### M2 — Party membership
- Perception: party invite, party update, leader change.
- Actions: accept/deny invite, join/leave, invite others, change leader.
- Party chat + party HP bar + follow-the-leader positioning.
- Register agents with `PartySearchCoordinator` so solo players can find them.
- **Exit criteria:** a solo player can party with an agent, chat in party, and
  move around maps together.

### M3 — Presence & combat assist
- Movement: pathing toward party/leader/mobs/portals via synthetic move packets.
- Combat: basic attack/support/buff/heal, potion use, looting, respawn.
- Warp/channel-follow with party (`warpParty`).
- **Exit criteria:** an agent can meaningfully help in low-level party content.

### M4 — Autonomous matchmaking & personas (**deferred / not in current scope**)
- Proactive: patrol/`partysearch`, initiate invites to nearby solos, greet.
- Larger persona library; memory summarization/compaction.
- Moderation hardening, cost controls, observability dashboard/logs.
- **Exit criteria:** solo players are matched with agents without manual GM
  intervention.

> Per Q6, agents are **strictly reactive** for now. M4 is recorded for later and
> must not be implemented in the first pass.

---

## 7. Testing & verification strategy

- **Unit:** reuse `src/test/java/testutil` (`HandlerTest`, `Packets`,
  `Mocks`) to test `AgentActionDispatcher` → synthetic packets, and
  `ScriptedAgentBrain` decisions. No DB/LLM needed.
- **Integration (local server):** boot with a dev config, spawn agents, connect
  a real client (or a second synthetic agent as the "solo player") and script a
  conversation + party invite flow. Assert on chat packets and party state.
- **Determinism:** default tests use `ScriptedAgentBrain`; LLM tests are opt-in
  and gated on an API key / local endpoint env var.
- **Soak/load:** N agents for M minutes; watch thread counts, LLM call
  concurrency, packet volume, and map lock contention.
- **Golden transcripts:** record agent observations/actions to replay for
  regression testing.
- **Manual acceptance:** real v83 client sees agents spawn/move/chat; party UI
  shows agents; party chat works.

---

## 8. Risks & mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| `PlayerLoggedinHandler` refactor breaks real login | High | Extract mechanically, keep handler delegating, add regression tests; do it in M0 before anything else |
| Cross-thread mutation of maps/characters | High | Per-agent serial executor; rely on existing locks; never touch Netty threads |
| LLM latency/cost/rate limits | Medium | Async, coalesced, bounded concurrency; scripted fallback; caching |
| Toxic / off-character output | Medium | Length clamp, content filter, kill switch, audit log |
| Anti-cheat false positives | Medium | Explicit, registry-gated bypass; keep gameplay validations |
| Agents corrupt party/PQ invariants | Medium | Route actions through real handlers; add tests |
| Agents mistaken for / trusted as humans | Medium | Distinct naming/labeling (Q8); optional `/agent` opt-out notice |
| DB noise / test characters | Low | Config-gated provisioning; dedicated agent accounts; cleanup command |
| Upstream non-goal conflict | Low | Default-off flag; isolate in `net/server/agent/` package |
| Packet decode effort creep | Medium | Prefer explicit hooks + state snapshots over decoding all outbound packets |

---

## 9. Decisions & remaining questions

**Resolved** — see the *Locked decisions* table at the top (Q1–Q8).

**Still open (do not block M0/M1):**

1. **Concurrency budget** — max simultaneous agents, max concurrent LLM calls,
   and acceptable per-agent tick/latency. Proposed defaults in §5.11
   (`LLM_AGENTS_MAX=4`, `LLM_MAX_CONCURRENT_CALLS=4`, tick 2000 ms); confirm.
2. **Deployment target** — local dev only, Docker included, and whether the
   server may make outbound network calls from inside the container. (Needed
   before wiring the real endpoint; local testing can use a local OpenAI-compatible
   server to avoid egress.)
3. **Memory schema choice** — dedicated `agent_memory` table vs. reusing
   `temp_data`. Recommendation: dedicated table (clearer, easier to query/clear).
4. **Persona provisioning source** — `LLM_AGENT_PERSONAS_FILE` (yaml/json)
   vs. DB rows. Recommendation: file for v1, DB later if M4 lands.

---

## 10. File-level touchpoints (anticipated, no code yet)

**New**
- `src/main/java/net/server/agent/AgentManager.java`
- `src/main/java/net/server/agent/AgentMemoryStore.java` (+ `agent_memory` table / migration)
- `src/main/java/net/server/agent/AgentPerception.java` (bounded/omniscient snapshot)
- `src/main/java/net/server/agent/AgentSession.java`
- `src/main/java/net/server/agent/AgentClient.java` (or under `client/`)
- `src/main/java/net/server/agent/AgentEventBus.java`
- `src/main/java/net/server/agent/AgentObservation.java`
- `src/main/java/net/server/agent/AgentActionDispatcher.java`
- `src/main/java/net/server/agent/brain/AgentBrain.java`
- `src/main/java/net/server/agent/brain/LlmAgentBrain.java`
- `src/main/java/net/server/agent/brain/ScriptedAgentBrain.java`
- `src/main/java/net/server/agent/AgentLoginService.java` (extracted bootstrap)
- `src/main/java/client/command/commands/gmN/AgentCommand.java`
- `docs/llm-agent-players-plan.md` (this file)

**Modified (small, surgical)**
- `src/main/java/client/Client.java` — overridable outbound write hook; null-safe
  `ioChannel` uses; `createAgent` factory.
- `src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java` —
  delegate to extracted login service.
- `src/main/java/net/server/channel/handlers/GeneralChatHandler.java`,
  `MultiChatHandler.java` — emit events.
- `src/main/java/net/server/world/World.java` (`partyChat`, `updateParty`) —
  emit events.
- `src/main/java/net/server/coordinator/world/InviteCoordinator.java` — emit
  party-invite events.
- `src/main/java/net/server/Server.java` — start/stop `AgentManager`.
- `config.yaml` — new agent keys.

**Tests**
- `src/test/java/net/server/agent/...` using existing `testutil`.
