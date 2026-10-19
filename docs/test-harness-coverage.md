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
| Party quests | 🟡 | KPQ probe: entry, stage-1 combat/quiz/passes/clear ✅; `EnterPortal` traverses scripted stage portals (stuck-transition flag fixed); remaining = no mob respawn on stage maps (mitigate via `spawn_mob`) + full multi-stage run pending |
| Hired merchants / minigames | ⛔ | PlayerInteraction room variants; protocols mapped but not yet implemented |

## Feature-list remainder (non-core, batched later)

- 🟡 PQs (HPQ/KPQ/…, expeditions, CPQ): KPQ feasibility probe passed entry + stage 1 fully
  (party, combat, quiz, passes, clear); `EnterPortal` now handles scripted stage portals and
  the stuck `isChangingMaps` flag that walled them was fixed. Remaining: no mob respawn on
  stage maps (mitigate via `spawn_mob`+kill loops) and full multi-stage completion runs.
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
5. ~~PQ feasibility probe (KPQ)~~ — **done**: entry + stage 1 clear; scripted portals now traversable (`EnterPortal`) and the stuck-transition flag fixed. Remaining: full multi-stage completion (mob respawn mitigated via `spawn_mob`).
6. Hired merchants / minigame rooms (PlayerInteraction variants).
7. Guild/alliance multi-session flows.
8. Full PQ completion scenarios once portal + respawn blockers are addressed.
