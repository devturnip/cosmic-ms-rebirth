# Test Harness — LLM-Agent E2E Testing Guide

Cosmic ships an **MCP (Model Context Protocol) server** that lets an external LLM agent
(pi / Claude Code / any MCP client) perform end-to-end gameplay tests against a real server —
no game client, no screenshots, no UI automation. The agent provisions ephemeral characters,
observes structured JSON state, submits actions that flow through the **real packet handlers**
(full server validation applies), and uses setup "cheat" ops to arrange scenarios.

> The feature is **default off**. A vanilla server (`USE_TEST_HARNESS: false`) has no test
> surface at all. Harness sessions are independent of the player-facing agent feature
> (`USE_LLM_AGENTS`) — they can be on/off in any combination.

Architecture and design rationale: `plan/2025-10-19/PLAN-00.md`. Code:
`src/main/java/net/server/agent/harness/`.

---

## 1. Configuration (`config.yaml`, `server:` block)

```yaml
USE_TEST_HARNESS: false          # master switch; also gates every setup op
TEST_HARNESS_MAX_SESSIONS: 4     # concurrent sessions (own cap, independent of LLM_AGENTS_MAX)
TEST_HARNESS_TRANSPORT: "http"   # "http" (streamable HTTP) | "stdio" (newline-delimited JSON)
TEST_HARNESS_HTTP_PORT: 8123     # http transport port
```

## 2. Running the server for tests

**Embedded (same host as the agent):** start MySQL via docker-compose (`docker compose up -d db`),
point `DB_URL_FORMAT` at `localhost:3307`, uncomment the `USE_TEST_HARNESS` block in `config.yaml`
(set `TEST_HARNESS_TRANSPORT: "stdio"`), then run the jar with `--test-harness-stdio` and attach
the MCP client to the process stdio.

**Docker-compose (remote/faithful):** uncomment the same block (set `TEST_HARNESS_TRANSPORT:
"http"`), rebuild the image (`docker compose build maplestory`) — older jars crash on the new
config keys — then `docker compose up -d maplestory` and point the MCP client at
`http://<host>:8123/mcp` (POST, JSON-RPC).

## 3. MCP tools

| Tool | Purpose |
| --- | --- |
| `session_start` `{jobId?, level?, mapId?, world?}` | Provision an ephemeral server-owned character and log it in through the real bootstrap. Returns the full observation. |
| `observe` `{sessionId}` | Full state: level/job/hp/mp/exp/mesos/position, monsters, drops, NPCs, players, inventory, equipment, quests (started/completed), buffered events. |
| `act` `{sessionId, actions[]}` | Submit player actions through the real handlers. Blocks until applied; returns per-action dispatch results + fresh observation. |
| `setup` `{sessionId, op, args}` | Cheat ops: `teleport`, `spawn_mob`, `kill_mob` `{withDrops}`, `list_mobs`, `grant_item`, `grant_mesos`, `set_level`, `set_quest_state`, `spawn_drop`, `heal`. |
| `drain_events` `{sessionId}` | Map chat, party invites, map changes, whispers received since last call. |
| `session_end` `{sessionId}` | Despawn + delete character and account (idempotent). |
| `session_list` | Live session ids. |

### Action vocabulary (`act`)

`Say{message}`, `PartySay{message}`, `Whisper{targetName,message}`, `InviteToParty{targetName}`,
`AcceptPartyInvite{partyId}`, `DenyPartyInvite{inviterName}`, `LeaveParty`, `MoveTo{x,y}`,
`Follow{targetName}`, `StopFollowing`, `PickupItem{objectId}`, `UseItem{itemId}`, `Respawn`,
`Attack{targetObjectId}`, `SkillAttack{targetObjectId,skillId}`,
`SkillAttackMany{targetObjectIds[],skillId}`, `RechargeAmmo`, `CastBuff{skillId}`,
`CastHeal{skillId}`, `Remember{fact}`, `Idle`.

Actions are executed exactly like client packets (`AgentActionDispatcher` → real handlers), so
invalid actions (wrong ammo, unreachable positions, cooldowns, mute) fail or get dropped by the
same validation a human player would hit. `act` returns `[true/false]` per action; a `false`
usually means the server refused something — check the observation to find out why.

**Test-harness sessions may loot** (unlike player companions, whose inventory dies with them),
which is what makes the drop→pickup→inventory flow testable.

## 4. Verified end-to-end walkthrough (live, 2025-10-20)

Actually executed against a running server (HTTP transport, `curl`):

1. `session_start` `{jobId:0, level:5, mapId:10000, world:0}` → `Agent-Rocket`, id 97.
2. `setup spawn_mob {mobId:100100, x:20, y:450}` → `objectId: 1000000005`.
3. `act [{MoveTo x:20,y:450}, {Attack targetObjectId:1000000005}]` → snail 8→7 HP through the
   real `CloseRangeDamage` path.
4. Repeat `Attack` ×7 → snail dead; observation shows 4 drops (arrows, mesos, snail shell, food)
   and `exp` increased.
5. `act [{PickupItem ...} ×4]` → inventory updated (`use` + `etc` lists), mesos 7500 → 7540.
6. `setup set_level 30` / `set_quest_state {questId:1000, state:"started"}` /
   `grant_item {itemId:1302000}` / `teleport {mapId:104000000}` / `heal` — all applied.
7. `session_end {sessionId:97}` → `characters` and `accounts` rows **0** (verified via SQL).

## 5. Exemplar scenarios (freeform)

The testing agent judges outcomes from observations — these recipes are the *starting points*:

- **Login & character creation** — `session_start` at various jobs/levels; verify observation
  (level, job, spawn map, starter inventory per `CompanionTemplate`); `session_end`; confirm
  `session_list` is empty.
- **Movement** — `MoveTo` across terrain, then check `x/y`; try an unreachable point and observe
  the partial step; cross-map via `setup teleport` + real portal walks on flat maps.
- **Combat & drops** — §4 walkthrough; also verify skill attacks consume MP, cooldowns block
  re-casts (`SkillAttack` returning false until ready), and death → `Respawn` works.
- **Skills & buffs** — `setup set_level` + provision with job skills; `CastBuff` then check
  `buffSkills` in observation; verify MP cost in `mp`.
- **Quests** — `setup set_quest_state` to arrange preconditions, then use `act` to progress
  through NPC-driven content; check `quests.started`/`completed`.
- **Equipment & inventory** — `setup grant_item`; verify counts in `inventory` maps; drop rules
  via `setup spawn_drop` + `PickupItem` ownership (FFA vs owned).
- **Cash shop** — the v83 cash shop is a separate server process; purchase flow is currently
  **deferred** (see coverage catalog). `grant_item` + `CHECK_CASH`-based verification is the
  interim path.

## 6. Guarantees & limitations

- **No interference with players or player-facing agents.** Harness sessions use their own gate
  and cap; they are server-owned ephemerals, never hijacking a live account; the provisioner
  refuses to touch accounts a human is using.
- **Ephemerality.** Every session's character/account is deleted on `session_end`; the
  `DbAgentProvisioner` startup reaper removes anything a crashed run left behind.
- **Harness sessions can enter scripted event instances** (PQs): the M9 companion guard
  (`isEphemeralAgent`) explicitly exempts harness sessions so party quests are testable.
- **Client-only verification is out of scope** — the harness tests server truth. Rendering,
  UI, and animation checks still need a real client (see the agent-players handoff for the
  packet-shape checks already done for agents).
- NPC *dialog* scripting is not yet driven over MCP (no `NPC_TALK`/`NPC_TALK_MORE` actions and
  no outbound-packet perception); quest testing via `set_quest_state` + direct quest packets is
  the interim path. See the coverage catalog for the deferred list.
