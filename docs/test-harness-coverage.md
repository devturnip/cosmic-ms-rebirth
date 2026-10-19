# Test Harness Coverage Catalog

Tracks which gameplay features are testable end-to-end via the test harness
(`docs/test-harness-guide.md`). Seeded from `docs/feature_list.md`; core features first.

Legend: ✅ covered · 🟡 partial (workaround exists) · ⛔ deferred · ❌ not testable without a client

## Core (PLAN-00 scope)

| Feature | Status | Notes |
| --- | --- | --- |
| Login / bootstrap | ✅ | `session_start` uses `PlayerLoginService` path |
| Character creation | ✅ | provisioned via `CharacterFactoryRecipe` + `insertNewChar` |
| Movement / maps | ✅ | `MoveTo`, `setup teleport`; portal-walk on flat maps |
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

## Feature-list remainder (non-core, batched later)

- 🟡 PQs (HPQ/KPQ/…, expeditions, CPQ): **KPQ completed end-to-end** (entry → stages 1-5 →
  bonus + rewards, 4 harness sessions, real handlers). Stage-map mob respawn still absent
  (mitigated via `spawn_mob`+kill loops); other PQ completion runs remain.
- ⛔ Guild/alliance creation flows (multi-session + match-checker coordination), marriage,
  family, New Year cards, quests with scripted cutscenes.
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

## Next additions (cheapest first)

1. ~~NpcTalk/NpcSelect actions + outbound-dialog perception~~ — **done** (live-verified).
2. ~~Second-session support (trade/buddy/party)~~ — **done** (live-verified).
3. ~~Cash-shop purchases (`grant_cash` + `EnterCashShop` + `CashBuy`)~~ — **done** (live-verified).
4. ~~Storage + Duey~~ — **done** (live-verified).
5. ~~PQ feasibility probe (KPQ)~~ — **done, superseded by a full completion run**: entry → stages 1-5 → bonus + rewards, live. Rect stages placed via `setup teleport {x,y}`; boss passes via `kill_mob{withDrops}` (now registers the kill's damage source so the loot filter spawns drops).
6. ~~Hired merchants / minigame rooms (PlayerInteraction variants)~~ — **done (live-verified)**: minigame create/visit/start/move/flip/forfeit/tie/exit and the full hired-merchant flow (create → stock → open → visitor buy → withdraw/take-back/close), all through the real `PlayerInteractionHandler` with state-delta verification and an `interaction` observation block; not exposed: player-shop permit rooms, ready/skip frames, merchant blacklist/visitor-history.
7. Guild/alliance multi-session flows.
8. Full completion runs for PQs other than KPQ.
