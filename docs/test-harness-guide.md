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
| `session_start` `{jobId?, level?, mapId?, world?, channel?}` | Provision an ephemeral server-owned character and log it in through the real bootstrap. Returns the full observation. Match `channel` to a real client's channel when testing cross-visibility. |
| `observe` `{sessionId, omniscient?}` | Full state: level/job/hp/mp/exp/mesos/position, monsters, drops (with name/questId), NPCs, players, inventory, equipment, quests, buddies, trade, cashShop, npcDialog, buffered events. Pass `omniscient: true` to lift the view-range cap (whole map). |
| `act` `{sessionId, actions[]}` | Submit player actions through the real handlers. Blocks until applied; returns per-action results + fresh observation. |
| `setup` `{sessionId, op, args}` | Cheat ops: `teleport`, `spawn_mob`, `kill_mob` `{withDrops}`, `list_mobs`, `grant_item`, `grant_mesos`, `grant_cash` `{amount, type?}` (1=NX credit, 2=maple point, 4=prepaid), `set_level`, `set_quest_state`, `spawn_drop`, `heal`, `debug_players` `{mapId?}` (diagnostic: server-side map/channel/world player lists — ground truth for visibility disputes). |
| `drain_events` `{sessionId}` | Map chat, party invites, map changes, whispers received since last call. |
| `session_end` `{sessionId}` | Despawn + delete character and account (idempotent). |
| `session_list` | Live session ids. |

### Action vocabulary (`act`)

**Player basics:** `Say{message}`, `PartySay{message}`, `Whisper{targetName,message}`,
`InviteToParty{targetName}`, `AcceptPartyInvite{partyId}`, `DenyPartyInvite{inviterName}`,
`LeaveParty`, `MoveTo{x,y}`, `Follow{targetName}`, `StopFollowing`, `PickupItem{objectId}`,
`UseItem{itemId}`, `Respawn`, `Attack{targetObjectId}`, `SkillAttack{targetObjectId,skillId}`,
`SkillAttackMany{targetObjectIds[],skillId}`, `RechargeAmmo`, `CastBuff{skillId}`,
`CastHeal{skillId}`, `Remember{fact}`, `Idle`.

**NPC & quests:** `NpcTalk{npcId}` (opens the dialog — requires the NPC on the agent's map),
`NpcSelect{selection}` (menus, yes/no 1/0, accept/decline 1/0, number answers),
`NpcRespondText{text}` (text-input frames only), `NpcEndDialog`,
`QuestStart{questId,npcId}` / `QuestComplete{questId,npcId}` (auto-detect scripted JS vs
WZ-only quests and fire the matching real client path; both require the quest NPC nearby),
`QuestForfeit{questId}`.

**Cross-visibility with real clients:** `session_start` accepts a `channel` (default 1) —
**match it to the channel the real client is on**. Map instances are per-channel; a harness
session on channel 1 is invisible to a client on channel 2 (this was the cause of a
"phantom sessions" report). `setup debug_players {mapId?}` dumps who the *server* thinks is
on the map / channel / world — the ground truth for visibility disputes. Observations include
the session's `channel`. (Harness MCP tool schemas previously declared every argument as a
string; they are now properly typed.)

**Social & economy:** `AddBuddy{name}` (v83 semantics: unilateral when the target has space),
`EnterCashShop`, `CashBuy{useNX,snCS}` (useNX: 1=NX credit, 2=maple point, 4=prepaid; fund
with `setup grant_cash`; the character leaves the map — end the session when done),
`TradeInvite{targetName}`, `TradeAccept`, `TradePutItem{inventoryType,slot,quantity,targetSlot}`,
`TradePutMesos{amount}`, `TradeConfirm` (two-session flow; staged state visible via the
`trade{partnerName,fullTrade,mesos,items[]}` observation; the exchange completes on the second
confirm), `StorageStore{slot,itemId,quantity}`, `StorageTakeOut{inventoryType,slot}` (0-based
storage slot), `StorageMesos{amount}` (+withdraw / −store), `StorageClose`,
`DueySend{inventoryType,slot,quantity,mesos,recipient,message?}`, `DueyAction{operation,
packageId}` (0=refresh, 4=claim, 5=remove). Storage/Duey require level 15+; open the storage
via `NpcTalk` to a storage NPC (1002005/1012009/1022005/...), Duey via NPC 9010009.

Actions are executed exactly like client packets (`AgentActionDispatcher` → real handlers), so
invalid actions (wrong ammo, unreachable positions, cooldowns, mute) fail or get dropped by the
same validation a human player would hit.

### `dispatched` vs `accepted` (important)

Each `act` result reports two flags:

- **`dispatched: true`** — the action's packet was synthesized and processed by the real
  handler. This alone does **not** mean the server agreed to it.
- **`accepted: true/false/null`** — the server actually applied the action, verified by
  comparing game state before/after (drop left the map, mob HP dropped, MP decreased, item
  quantity decreased, position changed, dialog advanced/closed, quest state changed, trade
  contents staged, cash inventory grew). `null` means acceptance is not cheaply observable
  (chat/social actions). A silent `accepted: false` is usually the server faithfully refusing
  something a real client would also be refused — e.g. picking up a **quest-bound drop**
  (`items[]` entries carry a `questId` for exactly this case) while not on that quest, casting
  a skill the character hasn't learned, or responding to a dialog frame of the wrong type
  (`NpcRespondText` on a `yes_no` frame is refused, like a real client).
- Dialog frames are decoded from the outbound packets the NPC/quest scripts emit, so
  `npcDialog{npcId, msgType, type, text, open, disposed}` always reflects the last frame the
  script sent. Frames **persist through script dispose** (quiz/confirm frames followed by an
  immediate dispose stay readable, with `open:false`/`disposed:true`) — a response that
  triggers a dispose verifies as `accepted: true`.

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
- **Quest scripts** — `QuestStart{questId,npcId}` runs the quest's JS script if one exists
  (`scripts/quest/<id>.js`), else the WZ-driven path; navigate the script's frames with the
  dialog actions, then `QuestComplete{questId,npcId}` once requirements are met (script or WZ
  checks decide). `QuestForfeit{questId}` abandons a started quest. The quest NPC must be on
  the agent's map, and a **~500 ms NPC-click cooldown** (`BLOCK_NPC_RACE_CONDT`) applies
  between interactions — a real agent's pacing is fine, but tight scripted loops need a sleep.
- **Party quests** — harness sessions are exempt from the companion PQ guard, and a Kerning PQ
  probe verified: party formation, entry via Lakelis (9020000, dialog-less under
  `USE_OLD_GMS_STYLED_PQ_NPCS`), stage-1 combat/loot, quiz answers, pass trading and stage
  clear. Known limits: scripted stage portals need no `MoveTo`-bypass (add a portal action if
  needed) and PQ stage maps don't respawn mobs — top up via labeled `grant_item`.
- **Storage & Duey** — live-verified: open/store/take-out/fees/mesos and parcel send/claim
  (fee = `Trade.getFee(mesos) + 5000` for message parcels; quick delivery needs ticket
  5330000). Note: the server does not enforce the storage UI being open (packet-forgery-only
  laxness, flagged upstream), and observation range may hide distant NPCs — use
  `observe{omniscient:true}` after teleporting.
- **Cash shop** — `setup grant_cash {amount}` → `EnterCashShop` (verify `cashShop.opened` and
  that the character is off the map) → `CashBuy{useNX:1, snCS}` (valid SNs live in
  `wz/Etc.wz/Commodity.img`; NX balance and owned items are in `cashShop`) → end the session
  when done (the CS-exit reconnect path is out of scope).
- **Equipment & inventory** — `setup grant_item`; verify counts in `inventory` maps; drop rules
  via `setup spawn_drop` + `PickupItem` ownership (FFA vs owned).
- **Cash shop** — see the scenario above; purchase flow is live-verified (NX deduction, CS
  inventory, invalid-SN refusal).

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
- **Cash-shop exit** re-connects the client to the channel server; the harness does not
  simulate that reconnect — end the session instead (the character is deleted anyway).
- **NPC click cooldown** — `BLOCK_NPC_RACE_CONDT` (500 ms default) throttles reopening NPC
  dialogs; `QuestScriptManager` and `NPCTalkHandler` both enforce it.

Coverage status per feature (including deferred items) lives in
[`test-harness-coverage.md`](test-harness-coverage.md).
