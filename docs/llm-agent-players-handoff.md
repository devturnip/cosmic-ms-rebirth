# LLM Agent Players — Developer Handoff

Audience: a fresh engineer or AI agent picking this work up cold.
Read this first, then `docs/llm-agent-players-plan.md` (the full design/plan).

---

## 0. TL;DR

Cosmic (MapleStory v83 server emulator, Java 21 / Maven / Netty) now supports
**in-process, connection-less "agent players"** driven by an OpenAI-compatible LLM.
An agent is a real `Character` logged in through the same bootstrap a human uses, whose
inbound actions are synthesized packets dispatched through the **real handlers**, and
whose perception is a mix of explicit event hooks and a range-limited map snapshot.

- **Branch:** `feature/llm-agent-players`
- **HEAD:** `0cc8eb539`
- **Status:** M0 + M1 done; M2 done; M3 partial (perception, movement, follow, loot).
  Combat (attack/buff/heal/potion/respawn), pathing, and warp-with-party are **not** done.
- **Default:** feature is **off** (`USE_LLM_AGENTS: false`). Nothing changes for vanilla play.
- **Build:** `mvn -q clean test` (green: 29 agent tests + existing suite).

---

## 1. Build, test, run

```bash
# from repo root
mvn -q -DskipTests compile        # fast compile
mvn -q clean test                 # full suite (includes net.server.agent.*)
mvn -q -Dtest='net.server.agent.*Test' test
```

Running the server (see `README.md` for full setup):
1. MySQL 8 + run `database/sql/1..4` then **`database/sql/migration/v0.15.0.sql`**
   (creates `agent_memory`; agents still work without it, memory persistence just logs errors).
2. Set `DB_PASS` (and optionally LLM keys) in `config.yaml`.
3. `net.server.Server#main` (or `docker compose up --build`).

Exercise agents in-game:
1. `USE_LLM_AGENTS: true`; set `LLM_API_URL` + `LLM_MODEL` (works with OpenAI/Azure/Ollama/
   llama.cpp/vLLM). Leave URL blank to use the deterministic `ScriptedAgentBrain`.
2. Create/choose a character in world 0 (name it with the `[AI]` prefix by convention).
3. Log in as a GM (rank 5+) and run:
   - `/agent spawn <name>` — brings the character online as an agent
   - `/agent list`
   - `/agent say <name> <text>` — GM instruction to the brain
   - `/agent follow <name> <playerName>` / `/agent stopfollow <name>`
   - `/agent search <name>` — register as a party-search leader (solos can find it)
   - `/agent despawn <name>`

---

## 2. Architecture

```
                    ┌──────────────────────────────────────────────┐
                    │                 AgentManager                 │  net/server/agent/AgentManager.java
                    │  registry · ticker · brain pool · event路由  │
                    └───────────────┬──────────────────────────────┘
                                    │ owns 1..N
                    ┌───────────────▼──────────────────────────────┐
                    │                 AgentSession                  │
                    │  inbox(AgentEvent) · deciding CAS · actionExec│
                    │  rate limiter · follow target                 │
                    └───────┬───────────────────────▲──────────────┘
             decide()       │                       │ enqueue(event)
                    ┌───────▼──────┐        ┌───────┴────────┐
                    │  AgentBrain  │        │  AgentEventBus │  (implicit: AgentManager hook methods)
                    │ Llm / Script │        └───────▲────────┘
                    └───────┬──────┘                │ hooks in
                            │ List<AgentAction>      │ chat/party/whisper/map
                    ┌───────▼────────────────────────┴──────────────┐
                    │           AgentActionDispatcher                │
                    │  synthesizes InPacket → real PacketProcessor   │
                    └───────┬───────────────────────────────────────┘
                            │
                    ┌───────▼───────────────────────────────────────┐
                    │  AgentClient extends Client (no socket)        │
                    │  + Character / Channel / World / MapleMap      │
                    └───────────────────────────────────────────────┘
```

**Outbound ("what the agent is told"):** `AgentClient` overrides `Client#writePacket`,
so all packets the server would send are captured/dropped instead of hitting a socket.
Currently packets are dropped; the seam exists for a future faithful-transcript feature.

**Inbound ("what the agent does"):** `AgentSession#dispatchInbound(RecvOpcode, InPacket)`
looks up the handler from the agent's `PacketProcessor` and calls it directly (after
`validateState`). This means normal validation/spam-guards apply.

**Perception:** `AgentPerception.observe(Character)` builds an `AgentObservation`
(players/monsters/items/NPCs in range, party, own stats). Chat/party/whisper/map events
arrive via lightweight hooks (see §5).

**Threading:**
- Decisions (possibly blocking LLM calls) run on `AgentManager`'s fixed `brainPool`
  (size `LLM_MAX_CONCURRENT_CALLS`). **Never** on a Netty event loop.
- Each `AgentSession` owns a single-thread `actionExecutor`; all game mutations for that
  agent happen there, serialized.
- `AgentManager` ticker (single thread) calls `AgentSession#tick` every `LLM_AGENT_TICK_MS`.
- Spawn/despawn are `synchronized` on `AgentManager`; `/agent` runs on `ThreadManager`.

---

## 3. File map

### New (all under `src/main/java/net/server/agent/`)
| File | Responsibility |
| --- | --- |
| `AgentManager` | Singleton: start/stop, spawn/despawn, event routing hooks, party-search + follow control, ticker + brain pool. |
| `AgentSession` | One agent: inbox, decide cycle, action execution, follow, rate limit. |
| `AgentClient` | `Client` subclass with no socket; overrides `writePacket`. |
| `AgentAction` | Sealed interface of actions (`Say`, `PartySay`, `Whisper`, `InviteToParty`, `Accept/DenyPartyInvite`, `LeaveParty`, `MoveTo`, `PickupItem`, `Remember`, `Idle`). |
| `AgentActionDispatcher` | Maps actions → synthetic inbound packets; content filter; audit log; rate-limit gate. |
| `AgentEvent` | Sealed interface of perceived events (`MapChat`, `PartyChat`, `Whisper`, `PartyInvite`, `PartyUpdate`, `MapChanged`, `Tick`, `Instruction`). |
| `AgentBrain` | Decision interface: `List<AgentAction> decide(observation, events, memory)`. |
| `LlmAgentBrain` | LLM implementation via tool calling; strictly reactive; fails safe (empty list). |
| `OpenAiChatClient` | Java 21 `HttpClient` + Jackson OpenAI chat-completions client + tool schema. |
| `ScriptedAgentBrain` | Deterministic brain (no LLM); used by tests and when no LLM is configured. |
| `AgentBrainFactory` | Chooses LLM vs scripted; injects persona system prompt. |
| `AgentObservation` | Immutable perception snapshot (records + nested observed entity records). |
| `AgentPerception` | Builds `AgentObservation` (BOUNDED range or OMNISCIENT). |
| `AgentMovement` | Synthesizes clamped `MOVE_PLAYER` packets; `stepToward`. |
| `AgentCombat` | Potion self-sustain, respawn, and a basic skill-0 melee attack packet. |
| `AgentMemory` | Per-agent facts + rolling dialogue (synchronized). |
| `AgentMemoryStore` | JDBC persistence for `AgentMemory` (`agent_memory` table). |
| `AgentPersona` | Persona record + JSON loader. |
| `AgentPersonaStore` | Cached JSON/YAML persona file loading (case-insensitive lookup). |
| `AgentConfig` | Typed access to `config.yaml` with safe defaults. |

### New (elsewhere)
- `src/main/java/net/server/PlayerLoginService.java` — channel bootstrap **extracted** from
  `PlayerLoggedinHandler`; used by both real logins and agent spawn (behavior-preserving).
- `src/main/java/client/command/commands/gm5/AgentCommand.java` — `/agent ...`.
- `database/sql/migration/v0.15.0.sql` — `agent_memory` table.
- `src/test/java/net/server/agent/*` — 8 test classes.
- `docs/llm-agent-players-plan.md`, this file.

### Modified (small, surgical)
- `client/Client.java` — added overridable `protected void writePacket(Packet)` (default still
  `ioChannel.writeAndFlush`), null-safe `closeSession`/`disconnectSession`/idle check,
  `getPacketProcessor()`.
- `client/Character.java` — `changeMapInternal` calls `AgentManager.onMapChanged`.
- `net/server/Server.java` — start/stop `AgentManager` when `AgentConfig.enabled()`;
  `getChannelDependencies()`.
- `net/server/world/World.java` — `partyChat` → `onPartyChat`; `updateParty(int,…)` →
  `onPartyUpdate` (events) + `refreshAutoFollow()` (post-mutation).
- `net/server/channel/handlers/{GeneralChatHandler,WhisperHandler,PartyOperationHandler}.java`
  — event hooks.
- `net/server/coordinator/session/SessionCoordinator.java` — `isAccountOnline(int)`.
- `config/ServerConfig.java` + `config.yaml` — agent config keys.
- `pom.xml` — `jackson-databind:2.17.2`.

---

## 4. Invariants & gotchas (do not break these)

1. **`PlayerLoginService.finishLogin` is a byte-for-byte extraction** of the old
   `PlayerLoggedinHandler` body. If you change login behavior, change it there.
2. **Never block a Netty event loop.** Agent work runs on `brainPool`/`actionExecutor`/
   `ThreadManager`. `/agent` is offloaded via `ThreadManager`.
3. **All agent chat goes through `sanitize()`**, which clamps to 127 chars and strips
   leading `/ @ !` — otherwise the agent can execute server commands or self-disconnect
   (autoban on >127-char chat). Do not bypass.
4. **Never spawn an agent whose character/account is already online** — `spawn` checks
   `PlayerStorage` + `SessionCoordinator.isAccountOnline`. `updateLoginState` force-disconnects
   duplicates, so this guard prevents kicking a real player.
5. **Despawn is synchronous** (`forceDisconnect`) to avoid a despawn→respawn race that
   deleted the new agent.
6. **Movement is clamped** to 16-bit + map area and capped at `MAX_STEP` (150px) per action —
   no teleport from hallucinated coordinates.
7. **Item pickup validates** the handler's range box (`|dx|≤800`, `|dy|≤600`) and ownership.
8. **Follow state is recomputed after party mutations** (`refreshAutoFollow`), and a manual
   `/agent follow` is sticky via `AgentSession.isManualFollow()`.
9. **`AgentMemory` is synchronized**; `AgentManager` maps are `ConcurrentHashMap`.
10. `AgentAction` is a **sealed interface** — adding a record requires updating the
    exhaustive `switch` in `AgentActionDispatcher#dispatch` (and the LLM tool mapping if
    you want the LLM to use it).
11. **Combat safety:** `AgentCombat.findPotion` only considers ids in `[2000000, 2060000)`
    and try/catches `getItemEffect` — projectile/food items have no `spec` and make the
    loader throw. Attack damage is `calculateMaxBaseDamage` (<= the handler's recalculation)
    and targets are only attacked well within the distance-alert threshold. `behaviorStep`
    charges **one** action-budget token per tick and isolates each sub-behaviour in a
    try/catch; do not remove either.

---

## 5. How to extend (recipes)

### Add a new action
1. Add a `record` to `AgentAction` (sealed interface).
2. Handle it in `AgentActionDispatcher#dispatch` (add a `case`).
3. Build the synthetic packet exactly matching the target handler's read order, e.g.:
   ```java
   send(session, RecvOpcode.SOME_OPCODE, p -> { p.writeByte(...); p.writeInt(...); });
   ```
   Verify against the handler's `handlePacket` reads.
4. (Optional) LLM: add the tool to `OpenAiChatClient#buildToolsSchema` and a `case` in
   `LlmAgentBrain#toAction`.
5. Add a unit test if the encoding is non-trivial (`Packets`/`InPacket` readers).

### Add a new perceived event
1. Add a `record` to `AgentEvent`.
2. Add a hook at the choke point, e.g. `AgentManager.getInstance().onX(...)`, and route it
   to the right sessions in `AgentManager`.
3. Render it in `LlmAgentBrain#describeEvent` (switch is exhaustive over `AgentEvent`).

### Add a perception field
1. Add a component to `AgentObservation` (append at the end to minimize churn) and a nested
   record if needed.
2. Populate it in `AgentPerception` (use `rangeSq()`, cap with `MAX_OBSERVED`).
3. Render it in `LlmAgentBrain#buildUserPrompt`.
4. Update the one test helper that constructs `AgentObservation` (`ScriptedAgentBrainTest`).

### Add a GM subcommand
Edit `AgentCommand.executeInternal` in `client/command/commands/gm5/AgentCommand.java`.
It already runs off the Netty loop.

---

## 6. Configuration (`config.yaml`, `server:` section)

| Key | Default | Meaning |
| --- | --- | --- |
| `USE_LLM_AGENTS` | `false` | Master switch (gates auto-start **and** `/agent spawn`). |
| `LLM_AGENTS_MAX` | `4` | Max simultaneous agents. |
| `LLM_AGENT_TICK_MS` | `2000` | Per-agent tick interval. |
| `LLM_AGENT_PERCEPTION` | `BOUNDED` | `BOUNDED` (range-limited, fair-ish) or `OMNISCIENT` (dev). |
| `LLM_AGENT_VIEW_RANGE` | `722500.0` | Squared view radius (850²) for BOUNDED. |
| `LLM_API_URL` | `""` | OpenAI-compatible chat-completions endpoint. Blank ⇒ scripted brain. |
| `LLM_API_KEY` | `""` | Bearer token (blank for local servers). |
| `LLM_MODEL` | `""` | Model name. |
| `LLM_MAX_CONCURRENT_CALLS` | `4` | Brain pool size / in-flight LLM requests. |
| `LLM_AGENT_NAME_PREFIX` | `[AI]` | Label convention for agent names. |
| `LLM_AGENT_CONTENT_FILTER` | `true` | Basic output filtering. |
| `LLM_AGENT_PERSONAS_FILE` | `""` | JSON/YAML persona file (see §7). |
| `LLM_AGENT_MEMORY_PERSIST` | `true` | Persist memory to `agent_memory`. |
| `LLM_AGENT_MAX_ACTIONS_PER_SEC` | `4` | Per-agent action rate limit. |
| `LLM_AGENT_REQUIRE_PREFIX` | `false` | Refuse spawn if name lacks the prefix. |
| `LLM_AGENT_FOLLOW` | `true` | Enable follow behavior. |
| `LLM_AGENT_AUTO_SUSTAIN` | `false` | Auto-use HP/MP potions when low. |
| `LLM_AGENT_AUTO_COMBAT` | `false` | Auto-attack the nearest monster. |
| `LLM_AGENT_HP_POTION_PERCENT` | `50` | HP % below which an HP potion is used. |
| `LLM_AGENT_MP_POTION_PERCENT` | `30` | MP % below which an MP potion is used. |
| `LLM_AGENT_ATTACK_RANGE_SQ` | `150000.0` | Squared auto-attack range (~387px). |

**Personas file** (JSON or YAML), either shape:
```json
{ "Alice": { "description": "cheerful cleric", "systemPrompt": "You are Alice..." } }
```
```yaml
- name: Alice
  description: cheerful cleric
  systemPrompt: You are Alice...
```

---

## 7. Database

`database/sql/migration/v0.15.0.sql`:
```sql
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `characterid` int(11) NOT NULL,
  `facts` text,
  `dialogue` text,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
```
Facts and dialogue are stored newline-delimited. `AgentMemoryStore` never throws on DB errors.

---

## 8. What's done vs. not

**Done**
- M0: headless puppet player, real bootstrap, `/agent spawn`.
- M1: reactive chat (general/party/whisper), LLM brain with tool calling, personas, memory.
- M2: party invite accept/deny/join/leave, party chat, follow-the-leader, party-search listing.
- M3 (partial): monster/item/NPC perception, movement, follow, item pickup, potion
  self-sustain, respawn, and a basic skill-0 melee attack.
- Ops: rate limiting, audit logging, content filter, config, GM command, wake hooks for
  map-change and party-update.

**Not done (next work)**
- **Combat depth:** skill-based attacks, buffs, heal-skill casting, ranged (`RANGED_ATTACK`)
  and magic (`MAGIC_ATTACK`) packets, multi-target/multi-line attacks, MP-cost handling,
  monster facing, flee/aggro management, and auto-looting kills.
- **Pathing:** only clamped straight-line steps; no footholds/portals.
- **Warp-with-party / cross-map follow.**
- **LLM movement policy** beyond `move_to`/follow.
- **M4:** proactive social behavior is deferred; note `/agent search` already triggers
  `PartySearchCoordinator` auto-invites (a form of matchmaking) — keep that in mind if you
  want strictly-reactive semantics.
- **Player-facing opt-out / bot notice** beyond the name prefix.

---

## 9. Review history

Two independent verifier passes were run; all HIGH/MEDIUM findings were fixed in
`54ef8c76f` and `0cc8eb539`:
- Command injection via `@`/`!` (now all headings stripped).
- Double-login / kicking real players (now guarded).
- Partial-login ghost on spawn failure (now rolled back).
- Async despawn race (now synchronous).
- Unclamped/teleport movement (now clamped + step-capped).
- Pre-mutation follow state (now `refreshAutoFollow` after mutation).
- Rate limiter races + `Remember` starving chat (now synchronized + free action).
- Pickup range mismatch / WARN spam (now validated).
- Uncapped nearby-player list (now capped).

Known low-risk remainder: `/agent search` enabling party-search auto-invites (documented),
siteless `/agent tell|mute|pause` (not implemented), and latent cross-thread map mutation
on very busy maps (soak-test target; map collections use read/write locks).

---

## 10. Git history (this branch, newest first)

| Commit | Summary |
| --- | --- |
| `367530db1` | combat assist (potions, respawn, basic melee attack) |
| `2994ee6a8` | developer handoff doc; plan marked implemented |
| `0cc8eb539` | M2/M3 review fixes + tests |
| `41c0c11b0` | perception, personas, movement/follow, loot, party search, rate limits |
| `54ef8c76f` | address first review findings |
| `d3be1c1bd` | OpenAI-compatible LLM brain, persistent memory, tests |
| `653f8fb77` | foundation (M0/M1 scaffold) |
| `9ad2b2d6c` | base (upstream `master`) |

Base branch: `master`. Suggested next branch: continue on `feature/llm-agent-players` or
branch `feature/llm-agent-combat` off it.
