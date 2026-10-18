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
| Cash shop | ⛔ | separate CS server process; needs session transfer support |
| NPC dialog scripting | ✅ | `NpcTalk` opens a dialog; observation exposes `npcDialog{npcId,type,text}`; `NpcSelect`/`NpcRespondText`/`NpcEndDialog` respond — live-verified against Roger (2101) |
| Trade / storage / Duey | ⛔ | needs partner sessions + more actions |
| Guild / alliance | ⛔ | needs multi-session + match-checker coordination |
| Buddy list | ⛔ | needs a second session |

## Feature-list remainder (non-core, batched later)

- 🟡 PQs (HPQ/KPQ/…, expeditions, CPQ): harness sessions are exempt from the companion PQ guard
  (`isEphemeralAgent`), so entry is possible; scenario scripts not yet written.
- ⛔ Marriage, family, hired merchants/minigames, New Year cards, quests with scripted cutscenes.
- ❌ Client rendering/animation/UI — permanently out of harness scope (use a real client).

## Next additions (cheapest first)

1. ~~NpcTalk/NpcSelect actions + outbound-dialog perception~~ — **done** (live-verified).
2. Second-session support (`session_start` twice) → trade, buddy, guild invitation paths.
3. Cash-shop session transfer → CS purchase tests.
