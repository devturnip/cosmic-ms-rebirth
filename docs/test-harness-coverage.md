# Test Harness Coverage Catalog

Tracks which gameplay features are testable end-to-end via the test harness
(`docs/test-harness-guide.md`). Seeded from `docs/feature_list.md`; core features first.

Legend: ✅ covered · 🟡 partial (workaround exists) · ⛔ deferred · ❌ not testable without a client

## Core (PLAN-00 scope)

| Feature | Status | Notes |
| --- | --- | --- |
| Login / bootstrap | ✅ | `session_start` uses `PlayerLoginService` path |
| Character creation | ✅ | provisioned via `CharacterFactoryRecipe` + `insertNewChar` |
| Movement / maps | ✅ | `MoveTo`, `setup teleport`; portal-walk on flat maps; Tier-2 routing (§4.20 in the agent handoff): `MoveTo` drives real `MOVE_PLAYER` fragments through the cached nav graph + v83 jump physics, so cross-platform targets (gap jumps, ropes) complete without teleports |
| Chat (map/party/whisper) | ✅ | `Say`/`PartySay`/`Whisper` + `drain_events`; opt-out honored |
| Party | ✅ | invite/accept/leave + `partyMembers` in observation |
| Combat (melee/skill) | ✅ | `Attack`/`SkillAttack` via real damage handlers; live-verified |
| Monster spawns / death | ✅ | `setup spawn_mob` / `kill_mob` `{withDrops}` |
| Item drops & pickup | ✅ | live-verified: kill → 4 drops → `PickupItem` → inventory |
| Inventory & equipment | ✅ | `grant_item` + inventory/equipment maps in observation |
| Skills / buffs / cooldowns | ✅ | `CastBuff`/`SkillAttack`; `buffSkills`, MP deltas |
| Quests (state machine) | ✅ | `setup set_quest_state`; `quests.started/completed` |
| Level / exp / stats | ✅ | `setup set_level`; `exp`, `level` in observation |
| Mesos | ✅ | drops, `grant_mesos` |
| Death & respawn | ✅ | `Respawn` action |
| Cash shop | ✅ | `grant_cash` + `EnterCashShop` + `CashBuy{useNX,snCS}` (real NX deduction + CS inventory); live-verified; exit path = session end |
| NPC dialog scripting | ✅ | `NpcTalk` opens a dialog; observation exposes `npcDialog{npcId,type,text}`; `NpcSelect`/`NpcRespondText`/`NpcEndDialog` respond — live-verified against Roger (2101) |
| Quest scripts (start/end/forfeit, kill-progress, text/number input) | ✅ | live-verified: 1021 full cycle, 20012 kill-progress, 21766 forfeit, level-gate + refusal paths |
| Trade (two sessions) | ✅ | `TradeInvite`→`TradeAccept`→`TradePutItem`/`TradePutMesos`→`TradeConfirm`; staged state + transfers live-verified |
| Buddy list | ✅ | `AddBuddy` (v83 semantics: unilateral when target has space) |
| Party / chat / whispers | ✅ | invite/accept + events via `drain_events` |
| Storage (account) | ✅ | `StorageStore/TakeOut/Mesos/Close` via real STORAGE packets; fees verified; UI-not-enforced laxness flagged upstream |
| Duey parcels | ✅ | `DueySend/DueyAction`; live-verified A→B with fee + DB row + claim; quick-delivery ticket rule documented |
| Party quests | ✅ | Full KPQ completion run live (4 harness sessions): entry via Lakelis, stage-1 quiz/coupons/passes/trade, rect stages 2-4 via `setup teleport {x,y}` + combo brute-force, stage-5 boss passes, bonus + rewards (lvl 25→34, item/exp gains verified); `EnterPortal{portalName:next00}` handles all stage transitions; `kill_mob{withDrops}` feeds the loot filter |
| Hired merchants / minigames | ✅ | PlayerInteraction rooms: `MiniGameCreate/RoomVisit/Start/MoveOmok/SelectCard/GiveUp/TieRequest/TieAnswer/RoomExit` + full hired-merchant owner/visitor flow (`HiredMerchantCreate/PutItem/Open/Buy/WithdrawMesos/TakeItemBack/Close`); **live two-session probe passed** (omok win + scores, match-card flip/points, merchant put/open/buy/meso-delta/withdraw/take-back/close, all verified via the `interaction` block); not exposed: player-shop (permit) rooms, ready/skip frames, blacklist/visitor-history |
| Reactors (hit / item-triggered) | ✅ | `ReactorHit{reactorObjectId}` via the real DAMAGE_REACTOR packet; verified via reactor state advance / destruction — unlocks PQ box-break stages (LPQ boxes 2202003, Ellin stage 5) |
| Monster catching (catch items) | ✅ | `CatchItem{mobObjectId,itemId}` via USE_CATCH_ITEM + handler validation (item↔mob pairing, HP threshold); verified via mob kill / item consumption — Ellin stage-4 marble flow |

## Feature-list remainder (non-core, batched later)

- 🟡 PQs (HPQ/KPQ/…, expeditions, CPQ): **KPQ completed end-to-end** (entry → stages 1-5 →
  bonus + rewards, 4 harness sessions, real handlers). Stage-map mob respawn still absent
  (mitigated via `spawn_mob`+kill loops). A live probe completed **HPQ and LPQ fully** and
  reached **Ellin 6/7**; the remaining gaps were closed afterwards: LPQ stage 2/5 (box breaking)
  and Ellin stage 4/5 now have organic paths via `ReactorHit`/`CatchItem`/`DropItem` (plus the
  content fixes below), so **Ellin is completable end-to-end** — a dedicated Ellin completion
  run is still to do.
- ✅ Guild (create/invite/join/leave/expel/ranks/notice/disband) & alliance
  (create via Lenario NPC/invite/expel/leave): `GuildCreate` + co-founder `GuildCreateAnswer`
  through the real match-checker confirmation (needs ≥ `CREATE_GUILD_MIN_PARTNERS` sessions on
  one map — raise `TEST_HARNESS_MAX_SESSIONS`), `GuildInvite`/`GuildAcceptInvite`,
  `GuildLeave`/`GuildExpel`/`GuildChangeRankTitle`/`GuildChangeRank`/`GuildNotice`, alliance
  via Lenario (2010009) + `AllianceInvite`/`AllianceAcceptInvite`/`AllianceLeave`; verified
  via the `guild`/`alliance` observation blocks (names, members, ranks, notice). Not exposed:
  emblem change (needs Guild HQ + 5M mesos, same handler family), guild-capacity increase,
  alliance rank/leader management packets.
- ✅ Marriage engagement & breakup: `Engage{targetName,itemId}` (level 50+, opposite gender via
  `setup set_gender`, engagement box 2240000+ granted) → `EngageAnswer{accept,proposerName}`
  (both `partnerId` + ETC boxes verified); `BreakEngagement{itemId}` severs (also divorces
  with a wedding ring). Full Cathedral/Chapel wedding ceremonies (reservation, invitations,
  guests, wishlist) remain 🟡 scenario-driven via the existing dialog/portal actions — they are
  scripted event flows, not single packets.
- ✅ Family: `FamilyAdd{name}` → `FamilyAccept{accept,inviterName}` (invite coordinator +
  family-creation handlers; `family` block shows senior/juniors/reputation; requires
  `USE_FAMILY_SYSTEM` and level rules: target >10, gap ≤ 20). Not exposed: family separation
  (SEPARATE_FAMILY packets), pedigree UI, family entitlements/summon response.
- ⛔ New Year cards, quests with scripted cutscenes.
- ❌ Client rendering/animation/UI — permanently out of harness scope (use a real client).

## QA-run findings folded back into the server

- **Item requirements without `count`** defaulted to 0, permanently blocking completion of
  quests like 1021 (Roger's Apple) even for real players — fixed (default now 1).
- **`Trade.inviteTrade` NPE** on a character's first trade invite (no `Trade` object yet) —
  fixed (auto-create); affected real players, not just the harness.
- **External-client visibility**: a sibling-client report claimed harness sessions were
  "phantoms" (no spawns/chat). Server-side probe (`debug_players`) showed harness sessions are
  fully registered in the map/channel/world player lists — the real cause was a **channel
  mismatch** (map instances are per-channel). `session_start` now takes a `channel` param and
  observations expose it; the report's schema string-vs-integer bug was also fixed.
- **Ellin PQ was uncompletable**: the stage-6 reactor script (`scripts/reactor/3001000.js`)
  spawns Poison Golem **9300180**, but `EllinPQ.js#isPoisonGolem` only accepted the
  Super-Charged variant **9300182** (which nothing spawns) — the clear condition could never
  fire. Fixed: both ids are accepted.
- **LPQ stage 3 was under-seeded**: map 922010300 has 8× Dirty Rat (9300007) — matching the
  vanilla `Map.wz` spawn set — each dropping **1**× LPQ pass (4001022), while the stage NPC
  (2040038) requires **32** passes. The 32 requirement was kept (it is the authentic stage-3
  rule and the 8-rat spawn set matches vanilla); the fix is the least-invasive data change:
  the 9300007→4001022 drop now yields **4** per rat (8 × 4 = 32), matching the classic
  "each rat drops 4 passes" stage-3 design. Vanilla-fidelity caveat: true drop data is not in
  WZ (this source keeps drops DB-side), so the per-rat quantity could not be verified against
  original data — documented here and in the seed file.
- **Ellin stage-4 mob id in NPC text**: the Ellin NPC says “weaken the #o9300174#” but map
  930000400 actually spawns Poison Flower **9300175** (the id the 2270004 marble handler
  pairs with); cosmetic NPC-string inconsistency, no code change.

## Next additions (cheapest first)

1. ~~NpcTalk/NpcSelect actions + outbound-dialog perception~~ — **done** (live-verified).
2. ~~Second-session support (trade/buddy/party)~~ — **done** (live-verified).
3. ~~Cash-shop purchases (`grant_cash` + `EnterCashShop` + `CashBuy`)~~ — **done** (live-verified).
4. ~~Storage + Duey~~ — **done** (live-verified).
5. ~~PQ feasibility probe (KPQ)~~ — **done, superseded by a full completion run**: entry → stages 1-5 → bonus + rewards, live. Rect stages placed via `setup teleport {x,y}`; boss passes via `kill_mob{withDrops}` (now registers the kill's damage source so the loot filter spawns drops).
6. ~~Hired merchants / minigame rooms (PlayerInteraction variants)~~ — **done (live-verified)**: minigame create/visit/start/move/flip/forfeit/tie/exit and the full hired-merchant flow (create → stock → open → visitor buy → withdraw/take-back/close), all through the real `PlayerInteractionHandler` with state-delta verification and an `interaction` observation block; not exposed: player-shop permit rooms, ready/skip frames, merchant blacklist/visitor-history.
7. ~~Guild/alliance multi-session flows~~ — **done + live-verified**: guild create/invite/join/leave/expel/rank/notice + alliance invite/join/leave through the real handlers, with `guild`/`alliance` observation blocks. Guild creation live-proved with 7 sessions (id 10 founded on the last acceptance, exact 1.5M meso cost, ranks/notice/expel/disband verified, zero DB residue). Alliance creation/disband remain dialog-driven via Lenario (2010009), documented as a scenario.
8. ~~Marriage engagement/divorce + family~~ — **done + live-verified**: `Engage`/`EngageAnswer`/`BreakEngagement` (RING_ACTION paths, partner/marriage-item state; engagement box is 2240000+, not a wedding ring) and `FamilyAdd`/`FamilyAccept` (family observation block; DB rows verified); full wedding ceremonies remain scenario-driven.
9. ~~Full completion runs for PQs other than KPQ~~ — **done, live-verified**: HPQ full organic pass (~9 min, seed reactors → bunny → Growlie → rewards); LPQ all 9 stages + bonus (incl. the C(9,5) stage-8 combo brute-force and organic ReactorHit pass sources after the box fix); Ellin full organic completion after the `isPoisonGolem` 9300180 fix (reactor → golem → clearPQ → out00).
