# PLAN-00 — LLM-Agent E2E Testing Harness for Cosmic

Date: 2025-10-19
Status: **Implemented & live-verified (2025-10-20)**

---

## 1. Goal

Enable LLM agents to perform **end-to-end testing of every core gameplay feature** of the
Cosmic v83 server emulator — without launching a real client, clicking UI, or processing
screenshots. The testing agent (pi / Claude Code in a terminal) drives the server through an
**MCP server surface** backed by the existing in-process agent-player infrastructure
(`net/server/agent`), which already routes agent actions through the real packet handlers and
provides structured perception (`AgentObservation`, `AgentEvent`).

### Why this approach

The painful status quo is: launch client → interact with UI → screenshot → interpret → act.
That loop is slow, lossy, and unusable for real-time combat. The repo already contains the
hard 80%: `AgentBrain` (pluggable decision boundary), `AgentActionDispatcher` (actions → real
inbound packets with full server validation), `AgentPerception` (structured world snapshot),
`AgentManager.spawn/despawn` (login through `PlayerLoginService`), and `DbAgentProvisioner`
(ephemeral server-owned characters). What is missing is an **external control surface** so a
*testing* agent — not an in-process LLM brain — can observe, act, set up scenarios, and assert.

## 2. Locked decisions

| # | Decision |
|---|----------|
| 1 | Control surface: **MCP** (tools callable from pi/Claude). |
| 2 | Server lifecycle: **both** — embedded in-process server for fast iteration, docker-compose real server for faithful verification. |
| 3 | Assertion model: **freeform** for now — the LLM observes state and judges outcomes; deterministic scripted scenarios with hard assertions deferred. |
| 4 | Scope: **core gameplay features** — login, character creation, skills, combat, quests, cash shop, item drops, equipment, inventory, movement, chat/party, leveling. "A full working game." |
| 5 | Setup ops: **yes** — the harness may "cheat" (teleport, grant items, spawn mobs, level up), gated behind a test-only config flag. |
| 6 | Test data: **ephemeral** — server-owned test accounts/characters created per session, deleted on teardown (same lifecycle as companions). |
| 7 | **No interference with player-facing agents**: harness sessions get their own gate (`USE_TEST_HARNESS`) and their own cap (`TEST_HARNESS_MAX_SESSIONS`) so they never consume `LLM_AGENTS_MAX` slots or require `USE_LLM_AGENTS` to be on. |

## 3. Constraints

- Java 21 / Maven / Netty; keep new dependencies minimal (MCP Java SDK is the one planned addition).
- All agent actions must continue to flow through **real handlers** (no bypassing server validation).
- Cheat/test ops must be **default-off** and impossible to enable on a production server (`USE_TEST_HARNESS: false` default, like `USE_LLM_AGENTS`).
- Ephemeral characters must be fully cleaned up (DB cascade + startup reaper, mirroring `DbAgentProvisioner`).
- Must not regress the existing agent-player feature; harness reuses its abstractions rather than forking them.
- CI (`mvn -q clean test`) must stay green; harness code needs unit tests around the MCP layer and test-ops.

## 4. Architecture

```
 LLM testing agent (pi / Claude Code)
        │  MCP (stdio for embedded runs, HTTP for docker-compose runs)
        ▼
 TestHarnessMcpServer  (new, in-process MCP server in the Cosmic JVM)
        │  tool calls: session_*, observe (omniscient?), act, setup, drain_events
        ▼
 TestHarnessService ──── gate: AgentConfig.USE_TEST_HARNESS (own flag & cap,
        │                 independent of USE_LLM_AGENTS / LLM_AGENTS_MAX)
        │
        ├── HarnessBrain (new AgentBrain)      — instead of an LLM, forwards
        │      AgentObservation/AgentEvent out and applies externally-supplied
        │      AgentAction lists (reuses AgentActionDispatcher → real handlers)
        ├── AgentManager.spawn/despawn         — session lifecycle, with a
        │      harness-kind flag so sessions bypass the LLM-agent cap and gate
        ├── DbAgentProvisioner (reused)        — ephemeral test chars/accounts
        └── TestOps (new, GM-equivalent, rank-free, flag-gated)
               teleport, spawn/kill mobs, grant items/mesos/skills,
               set level/exp, set quest state, fast-forward timers
```

### MCP tool surface (initial)

- `session_start(job, level, scenarioName?)` → provisions an ephemeral character, spawns it with a `HarnessBrain`, returns session id.
- `session_end(sessionId)` → despawn + delete character/account.
- `observe(sessionId)` → full `AgentObservation` as JSON (map, hp/mp, pos, nearby monsters/items/npcs/players, inventory, buffs, quests in progress).
- `act(sessionId, action[])` → submit `AgentAction`s (say, moveTo, attack with skill, useItem, pickupItem, npc talk/selection, equip/unequip, drop item, trade ops, quest accept/complete…).
- `setup_*` ops → `teleport`, `spawn_mob`, `grant_item`, `grant_mesos`, `set_level`, `set_quest_state`, `spawn_drop`, `set_time`.
- `wait_for(sessionId, condition)` → poll observation until predicate — **not implemented**; the
  testing agent polls `observe` instead (freeform tier), which keeps the MCP surface minimal.
  (`setup_*` ops are implemented as a single `setup` tool with an `op` argument.)

## 5. Requirements

### Functional
- R1 Testing agent can create/log-in an ephemeral character and observe the world as structured JSON.
- R2 Testing agent can issue any player-visible action and have it validated by the real handlers.
- R3 Testing agent can set up arbitrary preconditions (map, mobs, items, quest flags, level) without GM rank.
- R4 Sessions are ephemeral: full DB cleanup on `session_end` and on server startup (reaper).
- R5 Works against both an embedded server (in-JVM, temp DB) and the docker-compose stack.
- R6 Exemplar freeform scenarios for: login, character creation, movement, combat + drops, skills/buffs, quests, cash shop, equipment/inventory.

### Non-functional
- Harness disabled by default; enabling requires explicit config.
- No client or WZ-render dependency anywhere in the harness path.
- All new code unit-tested (`mvn -q -Dtest='net.server.agent.harness.*Test' test`).

### Edge cases & error handling
- Invalid action (bad itemId, unreachable move, wrong NPC state) → returned as a structured error to the agent, server state unchanged.
- Session crash/desync → `session_end` is idempotent; orphaned test accounts removed by reaper.
- `wait_for` → not implemented (agents poll `observe`; keeps the MCP surface minimal).
- Concurrent sessions on one channel → respect existing per-agent rate limits and a separate `TEST_HARNESS_MAX_SESSIONS` cap (never counted against `LLM_AGENTS_MAX`).
- Harness and player agents must be independently toggleable: `USE_TEST_HARNESS: true` + `USE_LLM_AGENTS: false` (and the reverse) are both valid configurations.
- Harness session action latency must not depend on the player-agent tick cadence (`LLM_AGENT_TICK_MS`); externally submitted actions are applied immediately, not on the 2s heartbeat.

### Integration points
- `AgentManager`, `AgentActionDispatcher`, `AgentPerception`, `DbAgentProvisioner`, `SessionCoordinator`, `Server` bootstrap, `config.yaml` (`AgentConfig`).

## 6. Work breakdown

1. **M0 — Harness brain + test-ops service**
   - `HarnessBrain implements AgentBrain` (external action inbox / observation outbox).
   - `TestOps` service (flag-gated cheat ops) with unit tests.
   - Session-kind support in `AgentManager.spawn`: harness sessions gated by `USE_TEST_HARNESS` and capped by `TEST_HARNESS_MAX_SESSIONS`, independent of `USE_LLM_AGENTS`/`LLM_AGENTS_MAX`; immediate (non-tick) action application for harness sessions.
2. **M1 — MCP server surface**
   - MCP Java SDK in-process server; stdio transport (embedded) + HTTP transport (docker-compose).
   - Tools per §4; JSON serialization of `AgentObservation` (+ inventory/quest extensions).
3. **M2 — Embedded server bootstrap**
   - Launch mode that boots Server + temp DB + minimal wz for fast harness runs; documented startup commands for both lifecycles.
4. **M3 — Session lifecycle & ephemerality**
   - `TestHarnessService` (provision/despawn/cleanup, caps, rate limits), startup reaper, idempotent teardown.
5. **M4 — Exemplar freeform scenarios**
   - Run and document each core feature scenario: login, char creation, movement, combat+drops, skills, quests, cash shop, equipment.
6. **M5 — Coverage catalog & docs**
   - `docs/test-harness-guide.md` (agent-facing) + coverage checklist mapped from `docs/feature_list.md`; note future tier (deterministic assertions).

## 7. Success criteria

- An agent with no game client can: start a session, create a character, level it, kill a mob, pick up the drop, equip the drop, accept/complete a quest, buy from cash shop — each verified purely from JSON observations.
- Full cycle against embedded server completes in seconds, not minutes.
- Zero leftover test rows in DB after `session_end` / restart.
- Existing agent-player tests and CI remain green.

## 8. TODO checklist

- [x] M0: Implement `HarnessBrain` (external action inbox / observation outbox) with unit tests — `net/server/agent/harness/HarnessBrain.java`
- [x] M0: Add harness session-kind to `AgentManager.spawn` — own `USE_TEST_HARNESS` gate, own `TEST_HARNESS_MAX_SESSIONS` cap, immediate action application (no player-agent tick dependency) — `spawnHarness()` + `AgentSession.isHarness()`
- [x] M0: Implement flag-gated `TestOps` cheat service (teleport, spawn/kill mobs, grant item/mesos/skill, set level, set quest state) with unit tests — plus `list_mobs`, `spawn_drop`, `heal`
- [x] M1: Add MCP server (stdio + HTTP transports), gated behind `USE_TEST_HARNESS` — *deviation: hand-rolled JSON-RPC on Jackson instead of the MCP Java SDK; zero new dependencies, both transports uniform*
- [x] M1: Define MCP tools: `session_start`, `session_end`, `session_list`, `observe`, `drain_events`, `act`, `setup`
- [x] M1: JSON-serialize `AgentObservation` plus inventory/quest/equipment extensions — `HarnessObservation`
- [x] M2: Embedded server bootstrap — `java -jar Cosmic.jar` (+ `--test-harness-stdio`) with `DB_URL_FORMAT` pointed at the docker DB; documented in `docs/test-harness-guide.md`
- [x] M2: Document docker-compose real-server startup path for harness runs (HTTP transport)
- [x] M3: `TestHarnessService`: ephemeral session provisioning, caps, rate-limit bypass for harness, idempotent teardown
- [x] M3: Startup reaper for orphaned test accounts/characters — reuses `DbAgentProvisioner.reapOrphans()` (same account prefix)
- [x] M4: Exemplar scenario — login & character creation (freeform, agent-verified)
- [x] M4: Exemplar scenario — movement & map interactions
- [x] M4: Exemplar scenario — combat, damage, mob death & item drops, pickup — **live-verified**: spawn snail → 7 real-handler attacks → kill → 4 drops → loot → inventory+mesos updated
- [x] M4: Exemplar scenario — skills, buffs, cooldowns, MP costs (action vocabulary + observation fields)
- [x] M4: Exemplar scenario — quests (accept, progress, complete) — `set_quest_state` + `quests.started/completed`
- [x] M4: Exemplar scenario — equipment & inventory (equip/unequip/drop/trade basics) — grant/observe verified; **full two-session trade later live-verified (round 2)**
- [x] M4: Exemplar scenario — cash shop purchase & retrieval — **live-verified**: `grant_cash` → `EnterCashShop` → `CashBuy` (NX 5000→4000→3000, CS inventory grows) → invalid SN refused; CS-exit reconnect documented as out of scope
- [x] M5: Write `docs/test-harness-guide.md` for testing agents
- [x] M5: Create coverage catalog mapped from `docs/feature_list.md` — `docs/test-harness-coverage.md`
- [x] Final: `mvn clean test` green (2191/2191 on Java 21 — note: the environment's default JDK 27 breaks Mockito, pre-existing); live E2E via MCP HTTP verified; DB verified clean after `session_end`

### Implementation notes (2025-10-20)

- New package `net/server/agent/harness/`: `TestHarnessConfig`, `HarnessBrain`, `TestOps`,
  `HarnessObservation`, `HarnessActions`, `TestHarnessService`, `TestHarnessMcpServer`.
- `AgentSession` gained a `harness` kind (no autonomous behaviour ticks, no per-second action
  throttle, may loot despite being ephemeral, synchronous `applyExternalActions`).
- `AgentManager.isEphemeralAgent()` exempts harness sessions so party quests are testable.
- `Character.getQuests()` widened from private to public for observation.
- Live verification used the docker DB on `localhost:3307` (temporary config edit, reverted)
  and the user's docker-compose server was stopped/restored around the run.

### Round 2 (2025-10-20, after three QA-agent runs — commits `151ba0f75`, `b17315909`)

- **Two server bugs found by QA agents and fixed** (would affect real players):
  `ItemRequirement` missing-count default (quest completion impossible, e.g. 1021) and
  `Trade.inviteTrade` NPE on a first invite.
- **New actions**: NpcTalk/NpcSelect/NpcRespondText/NpcEndDialog (dialog frames decoded from
  outbound packets into `npcDialog{npcId,type,text}`), QuestStart/QuestComplete (auto-detect
  scripted vs WZ-only), QuestForfeit, AddBuddy, EnterCashShop, CashBuy, TradeInvite/Accept/
  PutItem/PutMesos/Confirm. `grant_cash` setup op; `observe{omniscient}`.
- **Verifier** now state-verifies every observable action (dialog advanced, quest state,
  trade staging, cash inventory, buddy added).
- **Live-verified**: quest 1021 full cycle via real dialogs, quest 20012 kill-progress,
  text/number-input dialogs, forfeit, level-gate refusals, full two-session trade with
  transfers, cash-shop purchases with NX deduction, storage + Duey. 2195/2195 tests green.

### Round 5 (2025-10-20, KPQ completion attempt)

- EnterPortal{portalName} action added (real CHANGE_MAP_SPECIAL packet) — PQ stage portals
  are traversable; MoveTo deliberately skips scripted portals.
- The completion run bisected the true stage-portal blocker: a stuck `isChangingMaps` flag
  (harness sessions never send PLAYER_MAP_TRANSFER). The harness now clears it after every
  action and teleport; live-verified on KPQ stage 1 (EnterPortal reaches the portal script).
- EnterPortal verifier also fixed an Integer-vs-Long autoboxing bug that always reported
  accepted:true. Full multi-stage completion run still pending (machine move interrupted it).

### Round 4 (2025-10-20, heavenclient visibility report)

- External sibling client reported harness sessions as "phantoms" (no spawns/chat). A
  server-side probe (`debug_players`) disproved missing registration — sessions are in the
  map/channel/world lists; the real cause is a per-channel map-instance mismatch.
- `session_start` gained a `channel` param (was hardcoded 1); observations expose `channel`;
  `debug_players` diagnostic op added.
- MCP tool schemas now use proper types (integer/string/boolean/object) instead of declaring
  every argument as string (fixed the sibling report's schema complaint).

## 9. Open items / deferred

- Deterministic scripted scenarios with hard assertions for CI (tier 2).
- Non-core features from `feature_list.md` (PQ scenario scripts, expeditions, CPQ, guild PQ,
  marriage, storage/Duey/minigame room actions) — cataloged in
  `docs/test-harness-coverage.md`.
- Screenshot-free verification of client-side rendering is out of scope (server-side only).
- Cash-shop *exit* (channel reconnect) — purchases verified; exit = end the session.
