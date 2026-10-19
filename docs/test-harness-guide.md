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
| `observe` `{sessionId, omniscient?}` | Full state: level/job/hp/mp/exp/mesos/position, monsters, drops (with name/questId), NPCs, players, inventory, equipment, quests, buddies, trade, cashShop, npcDialog, interaction (minigame room / hired merchant state), guild, alliance, marriage, family, buffered events. Pass `omniscient: true` to lift the view-range cap (whole map). |
| `act` `{sessionId, actions[]}` | Submit player actions through the real handlers. Blocks until applied; returns per-action results + fresh observation. |
| `setup` `{sessionId, op, args}` | Cheat ops: `teleport` `{mapId, portal?, x?, y?}` — when `x`/`y` are both given the character is placed at that exact position on the target map (for PQ rect/area placements real movement cannot reach), `spawn_mob`, `kill_mob` `{withDrops}` (`withDrops` registers the session as the kill's damage source, so drops spawn even under `USE_SPAWN_RELEVANT_LOOT`), `list_mobs`, `grant_item`, `grant_mesos`, `grant_cash` `{amount, type?}` (1=NX credit, 2=maple point, 4=prepaid), `set_level`, `set_quest_state`, `spawn_drop`, `heal`, `set_gender` `{gender}` (0=male, 1=female — provisions default to male; arrange opposite-gender engagement pairs), `debug_players` `{mapId?}` (diagnostic: server-side map/channel/world player lists — ground truth for visibility disputes). |
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
`QuestForfeit{questId}`, `EnterPortal{portalName}` (scripted portals via the real
CHANGE_MAP_SPECIAL packet — required for PQ stage transitions, which `MoveTo` cannot walk
through). **`portalName` is the portal's NAME (e.g. `next00`, `st00`), not its script name
(e.g. `kpq1`)** — script names live in the map WZ's portal `script` property, while the
handler is routed by portal name.

**Map-transition note:** harness sessions never send the client's `PLAYER_MAP_TRANSFER`, so
the harness clears the `isChangingMaps` flag after every action and teleport — without this,
any map change would permanently block later portal entries.

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

**PlayerInteraction rooms (minigames & hired merchants):**
`MiniGameCreate{gameType:'omok'|'matchcard',description?,password?,pieceType}` creates a room
(the creator becomes owner; requires the matching item via `setup grant_item`: an Omok board
`4080000 + pieceType` for omok, `4080100` for match cards — pieceType also picks the board
size), `RoomVisit{ownerName,password?}` joins/enters any room on the agent's map by owner
name (minigame, player shop or hired merchant), `MiniGameStart` (owner),
`MiniGameMoveOmok{x,y,piece}` (piece 1=owner/2=visitor, coordinates 0..14),
`MiniGameSelectCard{turn,slot}` (turn 1 = first flip, 0 = second), `MiniGameGiveUp`,
`MiniGameTieRequest`, `MiniGameTieAnswer{accept}`, `RoomExit` (the generic EXIT — leaves any
room). Room state is exposed in the observation's `interaction` block (room type, role,
scores, the full Omok board / match-card points, merchant listings with the `index` used by
`HiredMerchantBuy`). Two-session omok flow: owner `MiniGameCreate` → visitor `RoomVisit` →
owner `MiniGameStart` → alternate `MiniGameMoveOmok` (a winning move ends the match and
clears the board) → `RoomExit`. Verification notes: omok moves verify via board state (a
winning move verifies via the match having ended), the match-card *first* flip via the
recorded slot and a scoring second flip via the points — a **non-scoring second flip only
broadcasts, so its `accepted` is `null`**; `MiniGameTieRequest` is likewise `null`.

**Hired merchants (two-session):** the owner teleports to a free-market room (maps
910000001+; `setup grant_item {itemId:5030012}` for the merchant item), then
`HiredMerchantCreate{description,itemId}` (maintenance mode) → `HiredMerchantPutItem{
inventoryType,slot,bundles,perBundle,price}` → `HiredMerchantOpen` (spawns the merchant box
on the map; the owner's room reference is released). A visitor `RoomVisit{ownerName}` →
`HiredMerchantBuy{itemIndex,quantity}` (quantity = bundles; verified via the buyer's meso
delta). The owner re-enters with `RoomVisit{ownerName:<self>}` (maintenance mode again) and
can `HiredMerchantWithdrawMesos`, `HiredMerchantTakeItemBack{slot}` or
`HiredMerchantClose` (returns the items; the box disappears from the map). Not exposed:
player-shop (permit 514xxxx) rooms and the merchant blacklist/visitor-history sub-menus.
Ready/unready/skip frames are not wrapped (START/GIVE_UP/TIE suffice to play a match).

**Guild / alliance / marriage / family (social systems):** `GuildCreate{name}` (GUILD_OPERATION
create — requires `CREATE_GUILD_MIN_PARTNERS` guild-less, party-free co-founders on the leader's
map plus `CREATE_GUILD_COST` mesos; opens a match-checker confirmation), `GuildCreateAnswer{
accept}` (each co-founder answers; the **last** acceptance founds the guild — the leader must be
at Guild HQ 200000301 and keeps leadership), `GuildInvite{targetName}` → invitee
`GuildAcceptInvite{inviterName}`, `GuildLeave{name}`, `GuildExpel{targetId,targetName}` (rank
1/2), `GuildChangeRankTitle{ranks[5]}` (rank 1), `GuildChangeRank{targetId,newRank}`,
`GuildNotice{notice}`. Guild state (id, name, rank, notice, members with ranks) is exposed in
the observation's `guild` block. Disbanding is NPC-driven: at Guild HQ, leader talks to Guon
(2010007) and picks “Disband your Guild” (or leaves via `GuildLeave` — note the leader leaving
without the NPC leaves a leaderless guild, faithful server behaviour). **Alliance:** creation
and disbanding run through Lenario (2010009) at Guild HQ with the dialog actions (two guild
masters partyed, the party leader pays 2M mesos, name via a text-input frame);
`AllianceInvite{guildName}` → the target guild's master `AllianceAcceptInvite{inviterName}`;
`AllianceLeave` (guild rank 1). Alliance state is in the `alliance` observation block.
**Marriage (engagement):** proposer `setup set_level {level:50}` + `setup grant_item
{itemId:2240000..2240003}` (engagement box), target `setup set_gender {gender:1}` + level 50,
same map → proposer `Engage{targetName,itemId}` → target `EngageAnswer{accept,proposerName}`
(on accept both gain the empty engagement box in ETC and `partnerId` is set on both; observe
`marriage{partnerId,marriageItemId,married}`). `BreakEngagement{itemId}` drops the engagement
token to sever (the same action divorces a married couple when given a 1112803x wedding ring).
**Family:** `FamilyAdd{name}` (same map, target level >10, level difference ≤ 20,
`USE_FAMILY_SYSTEM`) → target `FamilyAccept{accept,inviterName}` — on accept a family is
created/joined and appears in the `family` block (senior, juniors, reputation) of both sides.
**Full weddings** (Cathedral/Chapel reservation, invitations, guests, wishlist) are scripted
multi-stage event flows (`scripts/event/WeddingCathedral.js`) — not wrapped in dedicated
actions; drive them scenario-style with `NpcTalk`/`NpcSelect`/`EnterPortal` once a couple is
engaged, or treat the engagement + `BreakEngagement` as the covered surface.

**World interaction (reactors, catching, drops):** `ReactorHit{reactorObjectId}` (alias
`objectId`) hits an attack-type reactor via the real DAMAGE_REACTOR packet — this is what
breaks PQ boxes (e.g. the LPQ pass boxes 2202003) and item-reactors; it verifies via the
reactor's state advancing or the object being destroyed. `CatchItem{mobObjectId,itemId}`
uses a catch item on a mob via USE_CATCH_ITEM (the handler enforces the item↔mob pairing
and HP threshold — e.g. purification marble 2270004 on a Poison Flower 9300175 below 40%
HP, the Ellin stage-4 flow); accepted iff the mob was killed or the item was consumed.
`DropItem{inventoryType,slot,quantity}` drops an inventory stack via the real ITEM_MOVE
(action 0) path — full drop validation applies (GM rules, trade/minigame guards, stack
size); accepted iff the stack at the slot shrank.

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
- **Party quests** — harness sessions are exempt from the companion PQ guard. **A full Kerning PQ
  completion run was executed live (4 sessions, levels 25→34)**: party formation, entry via
  Lakelis (9020000), stage-1 coupon farming + quiz answers + pass trading, rect stages 2-4
  (members placed mid-rect via `setup teleport {x,y}`, combos brute-forced by the leader talking
  to Cloto), stage-5 boss passes (`spawn_mob` 9300002 + `kill_mob{withDrops}`), bonus map +
  `giveEventReward` prizes. Stage transitions via `EnterPortal{portalName:"next00"}` — the
  portal NAME, not its script name. Notes: the *first* leader talk on a rect stage only sets the
  secret combo (welcome message) and is not a check; the stage-1 quiz compares the coupon count
  **exactly** (shed extras via trade to a member with no quota, e.g. the leader); all sessions
  share one instance map, so drop pickups can race — distribute passes afterwards via trade.
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
- **Minigames (Omok / MatchCard)** — both sessions `setup grant_item {itemId:4080000}` (or
  4080100) on a normal map → owner `MiniGameCreate` → visitor `RoomVisit{ownerName}` → owner
  `MiniGameStart` → alternate `MiniGameMoveOmok` (board visible in `interaction.pieces`) →
  `MiniGameGiveUp`/win → `RoomExit`; scores accumulate per match and are visible in
  `interaction.ownerScore/visitorScore`.
- **Hired merchant** — owner: `setup teleport {mapId:910000001}` + `setup grant_item
  {itemId:5030012}` → `HiredMerchantCreate` → `HiredMerchantPutItem` → `HiredMerchantOpen`;
  visitor (same map/channel): `RoomVisit{ownerName}` → `HiredMerchantBuy` (mesos move, item
  enters inventory); owner: `RoomVisit{ownerName:<self>}` → `HiredMerchantWithdrawMesos` →
  `HiredMerchantClose` (box gone from the map, items back in `inventory`).
- **Guild & alliance (multi-session, ≥7 sessions — raise `TEST_HARNESS_MAX_SESSIONS`)** — all
  sessions `setup teleport {mapId:200000301}` (Guild HQ), no guilds, no parties: leader (with
  `setup grant_mesos {amount:1500000}`) `GuildCreate{name:"Cosmonauts"}` → each of the 6
  co-founders `GuildCreateAnswer{accept:true}` — the last acceptance founds the guild (verify
  via every session's `guild` block: leader rank 1, co-founders rank 2, mesos deducted). Then
  leader `GuildInvite{targetName}` → invitee `GuildAcceptInvite{inviterName}` (rank 5 member);
  `GuildChangeRank`/`GuildChangeRankTitle`/`GuildNotice`/`GuildExpel` verify via the `guild`
  block. Alliance: two guild leaders party up (one invites, one accepts via the party actions)
  and drive Lenario (2010009) with the dialog actions, paying 2M mesos; `AllianceInvite` /
  `AllianceAcceptInvite` / `AllianceLeave` for roster changes (`alliance` block).
- **Engagement & family (two sessions)** — A (male) and B (`setup set_gender {gender:1}`, both
  level 50+ via `setup set_level`) on one map: A `setup grant_item {itemId:2240000}` →
  `Engage{targetName:"B",itemId:2240000}` → B `EngageAnswer{accept:true,proposerName:"A"}` —
  both `marriage` blocks show `partnerId` set and the ETC engagement boxes appear in
  `inventory`. Severing: `BreakEngagement{itemId:4031357}` on either side resets `partnerId`.
  Family: A `FamilyAdd{name:"B"}` → B `FamilyAccept{accept:true,inviterName:"A"}` — both
  `family` blocks show the senior/junior link; levels must be within 20 and B above level 10.

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
