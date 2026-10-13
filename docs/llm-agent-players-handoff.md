# LLM Agent Players — Developer Handoff

Audience: a fresh engineer or AI agent picking this work up cold.
Read this first, then `docs/llm-agent-players-plan.md` (the full design/plan).
For a player/operator-facing walkthrough, see `docs/llm-agent-players-guide.md`.

---

## 0. TL;DR

Cosmic (MapleStory v83 server emulator, Java 21 / Maven / Netty) now supports
**in-process, connection-less "agent players"** driven by an OpenAI-compatible LLM.
An agent is a real `Character` logged in through the same bootstrap a human uses, whose
inbound actions are synthesized packets dispatched through the **real handlers**, and
whose perception is a mix of explicit event hooks and a range-limited map snapshot.

- **Branch:** `feature/llm-agent-players` (pushed, in sync with origin; working tree clean)
- **HEAD:** `161cdcd81` — starter profile enabled in `config.yaml` (guide + opt-out committed
  just before it)
- **Status:** M0–M4 done, plus a player-facing opt-out and a player/operator guide. The only
  open items are foothold/rope pathing depth and Hurricane/Rapid Fire after-delay +
  cast-animation fidelity — see **§11 Resuming the work** for a concrete checklist.
- **Defaults in source:** feature ships **off** (`USE_LLM_AGENTS: false`), proactive behavior
  **off** (`LLM_AGENT_PROACTIVE: false`). The committed `config.yaml` currently has the guide's
  **starter profile enabled** (`USE_LLM_AGENTS: true` + auto-combat/sustain/heal/buff/loot/flee)
  with blank `LLM_API_URL`/`LLM_MODEL`, so agents use the deterministic scripted brain until an
  endpoint is configured.
- **Build:** `mvn -q clean test` (green: 145 agent tests across 34 classes + full suite). Fast
  loop: `mvn -q -Dtest='net.server.agent.*Test' test`.

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
   (`agent_memory`) and **`database/sql/migration/v0.16.0.sql`** (`agent_optout`). Agents still
   work without them; memory/opt-out persistence just logs warnings.
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
- `spawn` is `synchronized` on `AgentManager`; `despawn` uses the concurrent map's atomic
  remove and does its DB I/O outside any monitor. `/agent` runs on `ThreadManager`.

---

## 3. File map

### New (all under `src/main/java/net/server/agent/`)
| File | Responsibility |
| --- | --- |
| `AgentManager` | Singleton: start/stop, spawn/despawn, event routing hooks, party-search + follow control, ticker + brain pool. |
| `AgentSession` | One agent: inbox, decide cycle, action execution, follow, rate limit. |
| `AgentClient` | `Client` subclass with no socket; overrides `writePacket`. |
| `AgentAction` | Sealed interface of actions (`Say`, `PartySay`, `Whisper`, `InviteToParty`, `Accept/DenyPartyInvite`, `LeaveParty`, `MoveTo`, `PickupItem`, `UseItem`, `Respawn`, `Attack`, `SkillAttack`, `SkillAttackMany`, `RechargeAmmo`, `CastBuff`, `CastHeal`, `Remember`, `Idle`). |
| `AgentActionDispatcher` | Maps actions → synthetic inbound packets; content filter; audit log; rate-limit gate. |
| `AgentEvent` | Sealed interface of perceived events (`MapChat`, `PartyChat`, `Whisper`, `PartyInvite`, `PartyUpdate`, `MapChanged`, `Tick`, `Instruction`). |
| `AgentBrain` | Decision interface: `List<AgentAction> decide(observation, events, memory)`. |
| `LlmAgentBrain` | LLM implementation via tool calling; the brain itself only reacts to given events (proactive behavior lives in `AgentProactivity`); fails safe (empty list). |
| `OpenAiChatClient` | Java 21 `HttpClient` + Jackson OpenAI chat-completions client + tool schema. |
| `ScriptedAgentBrain` | Deterministic brain (no LLM); used by tests and when no LLM is configured. |
| `AgentBrainFactory` | Chooses LLM vs scripted; injects persona system prompt. |
| `AgentObservation` | Immutable perception snapshot (records + nested observed entity records). |
| `AgentPerception` | Builds `AgentObservation` (BOUNDED range or OMNISCIENT). |
| `AgentMovement` | Synthesizes clamped `MOVE_PLAYER` packets; `stepToward`; `canWarpTo`/`warpTo` for cross-map follow. |
| `AgentCombat` | Potion self-sustain, respawn, auto-looting, ammo perception/resupply, and melee/magic/ranged basic + skill attacks (packet builders + damage/ammo/job gating + multi-target + multi-line + facing). |
| `AgentProactivity` | Opt-in proactive behaviors: greet nearby players, party-search matchmaking, patrol (all gated by `LLM_AGENT_PROACTIVE`). |
| `AgentSkill` | Buff-skill casting (0-target attack packet + `StatEffect` application) and heal-skill casting (`SPECIAL_MOVE` + party heal). |
| `AgentMemory` | Per-agent facts + rolling dialogue (synchronized). |
| `AgentMemoryStore` | JDBC persistence for `AgentMemory` (`agent_memory` table). |
| `AgentOptoutStore` | JDBC persistence for player opt-out (`agent_optout` table); loaded into `AgentManager` at start. |
| `AgentPersona` | Persona record + JSON loader. |
| `AgentPersonaStore` | Cached JSON/YAML persona file loading (case-insensitive lookup). |
| `AgentConfig` | Typed access to `config.yaml` with safe defaults. |

### New (elsewhere)
- `src/main/java/net/server/PlayerLoginService.java` — channel bootstrap **extracted** from
  `PlayerLoggedinHandler`; used by both real logins and agent spawn (behavior-preserving).
- `src/main/java/client/command/commands/gm5/AgentCommand.java` — `/agent ...`.
- `database/sql/migration/v0.15.0.sql` — `agent_memory` table.
- `database/sql/migration/v0.16.0.sql` — `agent_optout` table.
- `src/main/java/client/command/commands/gm0/AgentOptoutCommand.java` — player `/agentoptout`.
- `src/test/java/net/server/agent/*` — 23 test classes (85 tests).
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
- `net/server/coordinator/session/SessionCoordinator.java` — `isAccountOnline(int)`,
  `registerAgentClientIfAbsent(Client)` (atomic agent account claim); `onlineClients` is a
  `ConcurrentHashMap` and `updateOnlineClient` no longer disconnects the client it just stored.
- `server/StatEffect.java` — `hasDamage()` (whether WZ carried a `damage` node) so agents do
  not treat utility skills as attacks.
- `client/inventory/Inventory.java` — added `listCopy()`, a lock-protected inventory snapshot
  so perception can read the USE inventory off the brain thread without racing the action
  executor.
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
4. **Never spawn an agent whose character/account is already online.** `spawn` checks
   `PlayerStorage` + `SessionCoordinator.isAccountOnline`, then atomically claims the account
   with `registerAgentClientIfAbsent` after the (slow) DB load. Without that atomic claim a
   real player logging in concurrently would be force-disconnected by `updateLoginState`.
   Never let the agent be the one that kicks a real session.
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
    loader throw. Attack damage is conservative (server max can only be raised by buffs) and
    targets are only attacked well inside the distance-alert threshold. `behaviorStep`
    charges **one** action-budget token per tick and isolates each sub-behaviour in a
    try/catch; do not remove either.
12. **Attack routing is job/weapon/skill aware.** Basic (skill-0) attacks always use
    `CLOSE_RANGE_ATTACK` unless the agent wields a ranged weapon, because `MagicDamageHandler`
    NPEs on `SkillFactory.getSkill(0)`. Magic skills use `MAGIC_ATTACK` (chosen by
    `effect.getMatk() > 0`, so Cygnus/Evan work); physical skills use `RANGED_ATTACK` only
    when a ranged weapon and matching, level-valid ammo (>= the skill's `bulletCount`) are
    present. Out-of-ammo ranged agents skip rather than swinging melee.
13. **Attack skills are filtered** (`AgentCombat.isAttackSkill`): learned, not a buff, not
    `fixDamage`, magic requires `matk > 0`, physical requires `StatEffect#hasDamage()` (the WZ
    `damage` node actually exists — plain `getDamage()` defaults to 100 and would let Teleport/
    Shadow Web through), plus `EXCLUDED_ATTACK_SKILLS` (Meso Explosion, Shadow Meso, ranged-
    special skills). `canUseAttackSkill` also requires the skill's raw `mobCount > 0`.
    Otherwise a non-damaging skill trips `DAMAGE_HACK` (server max 0).
14. **Buff casting validates** via `canCastBuff` (learned, not cooling, `isSkill()`, statups,
    duration, MP/HP, not already active) before dispatching — `cast_buff` must never reach
    `skill.getEffect(0)`.
15. **Attack packets are byte-exact** against `AbstractDealDamageHandler#parseDamage`; the
    charge-skill list, the hurricane extra block, and the Poison Bomb tail are mirrored.
    Multi-target builders repeat the per-target block and pack the count into the high nibble
    of `numAttackedAndDamage`. Skill damage lines are `clamp(max(bulletCount, attackCount), 1..8)`
    — the same bound as the server's own "too many lines" guard — and repeated as that many
    identical damage ints per target. There are layout tests — keep parity when adding packets.
16. **Agents must not stay in the "changing maps" state.** `Character.mapTransitioning`
    defaults to `true` and is only cleared by the client's `PLAYER_MAP_TRANSFER` packet, which
    an agent never sends. `AgentManager.spawn` and `AgentMovement.warpTo` call
    `setMapTransitionComplete()`; any new code path that calls `Character#changeMap` for an
    agent must do the same or transition guards (`isChangingMaps()`) break.
17. **Cross-map follow is opt-in and guarded.** `LLM_AGENT_FOLLOW_WARP` (default off) warps a
    following agent to its target's map via `AgentMovement.warpTo`, which refuses dead/mid-
    transition agents, trades, open cash shops, event instances, and cross-channel targets.
    It uses the spawn-point `changeMap(int)` overload; there is a 4 s cooldown in `AgentSession`.
    This is trusted party-warp semantics (like `warpParty`) and deliberately skips portal/
    level/quest gating.
18. **Ammo resupply goes through the real shop.** `AgentCombat.rechargeAmmo` finds a shop NPC
    in `LLM_AGENT_RECHARGE_RANGE_SQ`, calls `Shop#recharge` for `ItemConstants.isRechargeable`
    items (throwing stars, bullets) and `Shop#buy` for arrows (which are not rechargeable in
    v83). Both pay mesos exactly like a player. The passive per-tick resupply is gated by
    `LLM_AGENT_AUTO_RECHARGE`; the explicit LLM `recharge_ammo` tool is always allowed.
19. **Movement is clamped per axis** (`AgentMovement.moveTo`), so a hallucinated destination can
    never teleport. `moveToSmart` aligns horizontally before crossing platforms. Cross-map
    follow first tries to walk to an **open, non-scripted** portal leading to the target map
    (`findPortalTo` skips closed/script portals) and enters it with the same event/trade/cash
    guards as `canWarpTo`; after `PORTAL_ATTEMPT_LIMIT` failed ticks it falls back to the
    `LLM_AGENT_FOLLOW_WARP` party-warp. Any new self-transfer must call
    `setMapTransitionComplete()`.
20. **Channel-follow re-logs the agent** via `AgentManager.transferChannel` (synchronized:
    capture state → synchronous `despawn` → `spawn` on the target channel). A failed re-spawn
    rolls back to the original channel; transfers are throttled per character and refused for
    null/mid-transition players. `AgentSession.behaviorStep` aborts after `followStep` if the
    session was despawned mid-step.
21. **Local skill cooldowns** (`AgentSession.isSkillReady`/`markSkillUsed`) mirror the WZ
    `cooldown`; cooldowns are only marked when `dispatchInbound` actually ran a handler.
22. **Proactive behavior is fully opt-in and cheap.** Every entry point in `AgentProactivity`
    is gated by `LLM_AGENT_PROACTIVE` plus its sub-switch, runs on the per-session action
    executor, and is self-throttled. `greet` selects read-only and marks a player only after
    the greeting was actually sent, skips agents/GMs/hidden players, and prunes its map.
    `matchmake` skips agents that are already following. Proactive actions never invoke the LLM.
23. **The LLM request budget is global and monotonic.** `OpenAiChatClient.tryConsumeCallBudget`
    enforces `LLM_MAX_CALLS_PER_MIN` (0 = unlimited) over a `System.nanoTime()` window; exceeding
    it throws, and the brain fails safe with an empty action list.
24. **Moderation survives transfers.** `AgentSession.setMuted` is an atomic volatile write;
    `AgentManager.transferChannel` captures and restores the muted flag. `dispatch` drops
    `Say`/`PartySay`/`Whisper` while muted **before** consuming rate budget or audit logging.

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
4. Update the test helpers that construct `AgentObservation` (`ScriptedAgentBrainTest` and
   `ScriptedAgentBrainLootTest`).

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
| `LLM_AGENT_AUTO_BUFF` | `false` | Auto-cast configured buff skills. |
| `LLM_AGENT_AUTO_BUFF_SKILLS` | `""` | Comma-separated buff skill ids. |
| `LLM_AGENT_ATTACK_SKILL` | `0` | Attack skill id for auto/LLM attacks (0 = basic). |
| `LLM_AGENT_AUTO_HEAL` | `false` | Auto-cast a heal skill when the agent/party is low. |
| `LLM_AGENT_AUTO_HEAL_SKILL` | `0` | Heal skill id (0 = auto-detect the learned heal skill). |
| `LLM_AGENT_HEAL_PERCENT` | `90` | HP % below which auto-heal fires. |
| `LLM_AGENT_AUTO_LOOT` | `false` | Auto-pick up owned/party/FFA drops every behaviour tick. |
| `LLM_AGENT_AOE_ATTACK` | `false` | Allow a multi-target skill attack to hit several monsters. |
| `LLM_AGENT_AOE_MAX_TARGETS` | `6` | Upper bound on monsters hit by one AOE attack. |
| `LLM_AGENT_FOLLOW_WARP` | `false` | Warp the agent to its follow target's map when they leave the current map. |
| `LLM_AGENT_AUTO_RECHARGE` | `false` | Each behaviour tick, recharge rechargeable ammo (stars/bullets) at a shop NPC for mesos. |
| `LLM_AGENT_RECHARGE_RANGE_SQ` | `150000.0` | Squared distance within which an NPC can act as a recharge shop (~387px). |
| `LLM_AGENT_AUTO_FLEE` | `false` | Retreat from monsters when HP drops below `LLM_AGENT_FLEE_PERCENT`. |
| `LLM_AGENT_FLEE_PERCENT` | `25` | HP % below which auto-flee fires. |
| `LLM_AGENT_FOLLOW_CHANNEL` | `false` | Follow the party leader across channels (re-logs the agent on the target channel). |
| `LLM_AGENT_PROACTIVE` | `false` | Master switch for proactive (non-reactive) behavior. |
| `LLM_AGENT_GREET` | `true` | With PROACTIVE on, greet nearby players once each. |
| `LLM_AGENT_MATCHMAKE` | `true` | With PROACTIVE on, auto-register for party search (auto-invites nearby solos). |
| `LLM_AGENT_PATROL` | `false` | With PROACTIVE on, wander the map when idle. |
| `LLM_AGENT_GREET_COOLDOWN_MS` | `600000` | Minimum ms between greetings of the same player. |
| `LLM_AGENT_MATCHMAKE_INTERVAL_MS` | `60000` | Minimum ms between party-search (re)registrations. |
| `LLM_AGENT_PATROL_INTERVAL_MS` | `8000` | Minimum ms between patrol steps. |
| `LLM_AGENT_GREETING` | `""` | Greeting template (`{name}` replaced); blank uses the brain/persona greeting. |
| `LLM_AGENT_MEMORY_SUMMARIZE` | `true` | Fold old dialogue into durable memory facts. |
| `LLM_MAX_CALLS_PER_MIN` | `60` | Global cap on LLM requests per minute (0 = unlimited). |
| `LLM_AGENT_NOTICE` | `false` | Broadcast a map notice that an agent is AI-controlled when it spawns. |

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

`database/sql/migration/v0.16.0.sql`:
```sql
CREATE TABLE IF NOT EXISTS `agent_optout` (
  `characterid` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
```
`AgentOptoutStore` loads the whole set into `AgentManager` at start and toggles rows on
`/agentoptout`; failures are logged, never thrown.

---

## 8. What's done vs. not

**Done**
- M0: headless puppet player, real bootstrap, `/agent spawn`.
- M1: reactive chat (general/party/whisper), LLM brain with tool calling, personas, memory.
- M2: party invite accept/deny/join/leave, party chat, follow-the-leader (same-map walk +
  cross-map warp), party-search listing.
- M4: opt-in proactive behavior (greet once per nearby player, auto party-search
  matchmaking that invites nearby solos, idle patrol), persona `greeting`/`traits`,
  memory compaction (single rolling summary fact), a global `LLM_MAX_CALLS_PER_MIN` request
  budget, `/agent status|mute|unmute`, and an optional map AI notice.
- M3 (complete): monster/item/NPC perception, clamped foothold-aware movement, follow
  (same-map walking, portal-based cross-map travel, opt-in cross-channel re-login), item
  pickup, potion self-sustain, respawn, melee/magic/ranged basic + skill attacks with
  multi-line damage (`max(bulletCount, attackCount)`), multi-target skill attacks (incl. the
  LLM `attack_many` tool), buff-skill casting, heal-skill casting, auto-looting, target
  facing, local skill cooldowns + HP/MP-cost gating, auto-flee, ammo perception
  (`rangedWeapon`/`ammoCount`/`outOfAmmo`) and shop resupply (recharge stars/bullets, buy
  arrows), plus LLM `follow`/`stop_following` movement tools.
- Player-facing opt-out: `/agentoptout` (rank 0) and GM `/agent optout <player>` suppress
  agent greetings, party-search invites, whispers, party invites, party chat and follow;
  persisted in `agent_optout` and loaded at start.
- Ops: rate limiting, audit logging, content filter, config, GM command, wake hooks for
  map-change and party-update.

**Not done (next work)** — see **§11** for file pointers and suggested approaches.
- **Pathing depth:** movement is clamped waypoint stepping (per-axis clamped, horizontal
  alignment before drops) with portal travel for cross-map follow; it is not a full
  foothold/rope pathfinder (no rope/ladder model exists server-side); patrol randomizes X only.
- **Combat fidelity:** Hurricane/Rapid Fire after-delay timing and cast-animation stance.
- **`/agent tell` / `/agent pause`:** not implemented (`mute`/`unmute` are).
- **Persistence guidance:** the two agent migrations are manual (like other repo migrations);
  without them, memory/opt-out changes simply do not survive a restart.


---

## 9. Review history

Independent verifier passes were run for each milestone (each followed by a fixes commit); all
HIGH/MEDIUM findings were addressed:
- **Foundation/M1** (`54ef8c76f`): command injection via `@`/`!`; double-login kicking real
  players; partial-login ghost; async despawn race.
- **M2/M3** (`0cc8eb539`): unclamped/teleport movement; pre-mutation follow state; rate-limiter
  races and `Remember` starving chat; pickup range mismatch; uncapped player list.
- **Combat** (`a72a98f33`): `findPotion` NPE on spec-less items; percentage potions;
  event-aware respawn; per-tick single rate charge; error isolation; range enforcement.
- **Buffs** (`b89f75a3e`): unvalidated `cast_buff`; charge-list packet desync; cooldown/HP-cost;
  `isSkill()` active-buff match.
- **Skills** (`ad2ddacb4`): skill-0 magic NPE; non-damaging-skill autoban; Meso/Shadow/ranged
  exclusions; Cygnus/Evan opcode; LLM fallback.
- **Ranged** (`8f0a6b9e5`): ammo type/level/bulletCount mismatch; Shadow-Claw no-op;
  magic-skill misrouting; out-of-ammo melee fallback; Poison Bomb tail.
- **Combat depth 2** (`ac779b70a`): heal-skill casting via `SPECIAL_MOVE`; auto-looting of
  owned/party/FFA/expired drops; multi-target skill attacks gated by `LLM_AGENT_AOE_ATTACK`.
  Fixes from two further verifier passes: spawn/login TOCTOU (`registerAgentClientIfAbsent`),
  `lastActions` scheduling race, damage-less-skill misclassification (`StatEffect#hasDamage()`),
  `AUTO_BUFF`-only never ticking, loot owner rule now matches `Character#pickupItem`, and
  `cast_heal` now resolves skill id 0.
- **Cross-map follow** (`2fb00ad3c`): warps a following agent to its target's map when they leave
  (`LLM_AGENT_FOLLOW_WARP`, default off), guarded by `AgentMovement.canWarpTo`. Verifier fix:
  agents were permanently `isChangingMaps()` (they never send `PLAYER_MAP_TRANSFER`), which made
  the warp unreachable and would also have broken it after the first warp — now cleared at spawn
  and after each warp; also switched to the spawn-point overload to avoid a portal-0 NPE.
- **Player opt-out**: two verifier passes. Fixed: party chat (both directions) and follow were
  un-gated bypasses (party chat now checks the speaker and drops party-say when a member has
  opted out; follow resolves and refuses opted-out targets, including auto-follow); the
  `/agentoptout` command no longer does JDBC on the Netty loop (offloaded to `ThreadManager`);
  `AgentOptoutStore` catches `Exception` (not `Throwable`) and uses `ON DUPLICATE KEY UPDATE`;
  and the greet test was a false positive (it never stubbed `isGreetDue`).
- **M4** (proactive matchmaking, personas, memory compaction, cost controls, notice, mute):
  three verifier passes. Fixed: `greet` marked every candidate as greeted (now selects read-only
  and marks only the successfully-greeted player); `matchmake` registered agents that were
  already following a leader (now skips); memory `compact` appended a new summary fact each time
  and could evict real facts (now replaces a single rolling summary, code-point-safe truncation);
  the LLM budget used wall-clock (now `nanoTime`); mute was lost across channel transfer (now
  captured/restored) and used a racy toggle (now an atomic `setMuted`); agents could auto-invite
  other agents via party search (now excluded).
- **M3 complete** (facing, cooldowns, HP-cost, arrow purchase, flee, smart movement + portal
  follow, channel-follow, LLM follow tools): three verifier passes. Fixed: portal branch wedged
  follow and bypassed the event/trade/cash guards (now skips closed/scripted portals, shares
  `canSelfTransfer`, and falls back to the warp after N attempts); `behaviorStep` kept running
  on a despawned session after a channel transfer (now aborts); failed channel transfers
  permanently deleted the agent (now rolls back to the original channel, throttled per
  character); `despawn(int)` races (now synchronized); battle cooldowns were marked even when a
  packet was dropped by `validateState` (now only on a handled dispatch); AOE facing re-fetched
  a possibly-despawned target (now captured during validation); arrow purchase aborted on the
  first unaffordable stock line (now scans on). `MapleMap.portals` is now a
  `ConcurrentHashMap` for safe iteration.
- **Combat depth 3** (multi-line + LLM multi-target + ammo): two verifier passes. Fixed
  `damageLines` to mirror the server's `max(bulletCount, attackCount)` guard (was `attackCount`,
  which under-sent Triple Throw/Strafe/Burst Fire by 2–4×); replaced the free `getSlotMax` refill
  with the real `Shop#recharge` meso path (and only `isRechargeable` stars/bullets); bounded the
  LLM target list (defensive-copy + cap at 15, `intArray` cap); made `outOfAmmo` use the largest
  required ammo among learned attack skills; honoured Shadow Claw alongside Soul Arrow; and added
  `Inventory#listCopy()` so ammo perception cannot race the action executor.

Known low-risk remainder: `/agent search` and auto-matchmaking enable party-search
auto-invites (documented); `/agent tell`/`pause` are not implemented (`mute`/`unmute` are);
movement is clamped waypoint stepping, not a full foothold/rope pathfinder (no rope/ladder
model exists server-side); and there is latent cross-thread map mutation on very busy maps
(soak-test target; map collections use read/write locks).

---

## 10. Git history (this branch, newest first)

| Commit | Summary |
| --- | --- |
| `161cdcd81` | config: enable the agent starter profile from the guide |
| `e3013298c` | docs: docker does not auto-run migrations |
| `69205e862` | docs: add a player/operator usage guide |
| `4f3b6fa62` | docs: reconcile handoff/plan with the shipped opt-out |
| `b8a9d8105` | opt-out: serialize startup load against toggles |
| `a757e52e5` | docs: document the player opt-out |
| `9a1067173` | player-facing opt-out from AI-agent interaction |
| `4cb347332` | M4: proactive matchmaking, personas, cost controls, moderation |
| `fb6f7d246` | finish M3: combat depth, pathing/portal follow, channel-follow, LLM follow |
| `45dd0e2d2` | multi-line + LLM multi-target attacks, ammo perception/recharge |
| `2fb00ad3c` | cross-map follow (warp-with-party) |
| `ac779b70a` | heal casting, auto-loot, multi-target attacks + review fixes |
| `8f0a6b9e5` | ranged-attack review fixes + ranged tests |
| `b57d7c8c0` | ranged attacks |
| `ad2ddacb4` | skill-attack review fixes + skill tests |
| `53503ecb0` | melee/magic skill attacks |
| `b89f75a3e` | buff review fixes + buff skills in perception |
| `41805f1ff` | buff skill casting |
| `a72a98f33` | combat review fixes + combat tests |
| `367530db1` | combat assist (potions, respawn, basic melee attack) |
| `2994ee6a8` | developer handoff doc; plan marked implemented |
| `0cc8eb539` | M2/M3 review fixes + tests |
| `41c0c11b0` | perception, personas, movement/follow, loot, party search, rate limits |
| `54ef8c76f` | address first review findings |
| `d3be1c1bd` | OpenAI-compatible LLM brain, persistent memory, tests |
| `653f8fb77` | foundation (M0/M1 scaffold) |
| `9ad2b2d6c` | base (upstream `master`) |

Base branch: `master`. Continue on `feature/llm-agent-players` (it is pushed and in sync).

---

## 11. Resuming the work (fresh-agent checklist)

M0–M4 plus the player opt-out are implemented, reviewed, tested and pushed. If you are picking
this up cold:

1. Read `docs/llm-agent-players-guide.md` (behavior + commands), then this file (§2 architecture,
   §3 file map, §4 invariants, §5 recipes). The design/plan is `docs/llm-agent-players-plan.md`.
2. Confirm the baseline: `mvn -q clean test` (green; 145 agent tests). Fast loop:
   `mvn -q -Dtest='net.server.agent.*Test' test`. Compile only: `mvn -q -DskipTests compile`.
3. Implement on this branch. Follow the repo's review pattern: implement → run an *independent*
   read-only verifier pass (spawn a subagent) → fix HIGH/MEDIUM findings → update docs →
   commit → push.

### 11.1 The only open work

| Item | Where | Notes / suggested approach |
| --- | --- | --- |
| **Foothold/rope pathing depth** | `AgentMovement` (`moveToSmart`, `stepToward`, `stepAway`, `findPortalTo`, `walkThroughPortalTo`); maybe `FootholdTree` | Movement is clamped per-axis waypoint stepping with horizontal-align-before-drop; cross-map follow walks to an open portal then falls back to a party warp. There is **no rope/ladder model server-side** and `MovePlayerHandler` is client-authoritative on position (it just sets x/y), so a full pathfinder is mostly cosmetic. `Foothold` exposes `getX1/X2/Y1/Y2`, `calculateFooting(x)`, `getNext/getPrev`; `FootholdTree` has `findBelow/findWall` but **no** enumeration — add `getAllFootholds()` if you build a graph. |
| **Hurricane/Rapid Fire after-delay + cast animation** | `AgentCombat` (`damageLines`, `buildAttackPacket`/`buildRangedAttackPacket`), `AgentSkill` (buff/heal packets) | `StatEffect` exposes no `getAfterDelay()` in this codebase; if pacing is needed, use `AgentSession.isSkillReady`/`markSkillUsed`. Packet `stance` is hard-coded `4` (Hurricane rebroadcasts stance = rangedirection). |
| **`/agent tell` / `/agent pause`** | `AgentCommand`, `AgentManager` | `mute`/`unmute` exist; `pause` would suspend decisions (e.g. an `AgentSession` "paused" flag checked in `tick`), `tell` would be an out-of-band instruction distinct from `say`. |
| **Fidelity/test gaps** | `AgentActionDispatcher.buyAmmoFromShop` (untested), opt-out routing gates `onMapChat`/`onWhisper`/`onPartyInvite` (untested — need registered sessions), `AgentManager.transferChannel` (no integration test) | Add seams/tests if you touch these paths. |

### 11.2 Environment state (as of the latest session)

- **Database:** the project MySQL container is `cosmic-ms-rebirth-db-1` (`mysql:8.4.0`, host
  port **3307** → container 3306, schema `cosmic`; root password in `config.yaml`). Both agent
  tables are now applied and present: `agent_memory` (v0.15.0) and `agent_optout` (v0.16.0).
  `docker compose`'s init dir (`database/sql`) does **not** run the `migration/` subdir, so
  apply migrations manually:
  ```bash
  docker exec -i cosmic-ms-rebirth-db-1 mysql -uroot -p<pw> cosmic < database/sql/migration/v0.15.0.sql
  docker exec -i cosmic-ms-rebirth-db-1 mysql -uroot -p<pw> cosmic < database/sql/migration/v0.16.0.sql
  ```
  If you run the server **locally** (not via compose), note `config.yaml` uses `localhost:3306`
  while the container maps host **3307** — point the JDBC URL at 3307, or run via
  `docker compose` (which sets `DB_HOST=db`).
- **Config:** `config.yaml` has the guide's starter profile enabled (`USE_LLM_AGENTS: true`,
  auto-combat/sustain/heal/buff/loot/flee) with blank `LLM_API_URL`/`LLM_MODEL` ⇒ agents use
  the **scripted** brain. Set an OpenAI-compatible endpoint for real LLM behavior.
- **Branch state:** `feature/llm-agent-players` is pushed and in sync with origin; worktree clean.

### 11.3 Definition of done for a change

`mvn -q clean test` green; an independent verifier pass with HIGH/MEDIUM findings addressed;
`handoff` + `plan` updated (and `guide` if user/operator-facing); worktree clean; committed and
pushed with a `feat(agent):` / `fix(agent):` / `docs(agent):` message.
