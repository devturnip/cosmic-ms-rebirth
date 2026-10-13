# LLM Agent Companions — Player-Facing Plan

Status: design finalized (decisions locked), M5/M6 in progress.
Read `docs/llm-agent-players-handoff.md` (§2 architecture, §4 invariants) first; this
document only covers the *player-facing* layer on top of the existing agent engine.

---

## 0. Problem

The shipped agent feature is an **operator tool**: agents are pre-existing logged-out
characters and only a GM can bring them online (`!agent spawn`). That defeats the purpose of
solo play, which needs a player to be able to get a level-appropriate companion on demand.

Three concrete gaps:

1. **Operator dependency** — a solo player cannot summon an agent.
2. **Level/quest fit** — a fixed human character is rarely the right level/job for a given
   player, and level mismatch breaks party exp and party-quest eligibility.
3. **Discovery/matching** — there is no player-facing way to find or request a companion.

---

## 1. Locked decisions

| # | Decision | Consequence |
| --- | --- | --- |
| 1 | **Ephemeral by default**; GM-spawned agents stay persistent | Player companions are created on demand and deleted on dismiss. The GM path (`!agent spawn <name>`) keeps the current persistent-character behaviour. |
| 2 | Ephemeral agents do not need to keep progression; GM-spawned agents keep earned level/mesos | No pooled-level drift problem: a companion is generated at the requester's level and its exp/meso die with it. |
| 3 | **Exp/meso are shared normally** (ordinary party split) | No special suppression. Fairness rests on ephemeral lifecycle + anti-AFK guards, not on value redirection. |
| 4 | **Random names/personas** | Provisioner invents a name and samples a persona/traits set. |
| 5 | **Companions only** | Agents fight/heal/buff/follow and fill party slots; they never advance the player's quest objectives or interact with quest NPCs on the player's behalf. |
| 6 | **LLM brain** | Player companions use `LlmAgentBrain` (persona-prompted). The global `LLM_MAX_CALLS_PER_MIN` / `LLM_AGENTS_MAX` budgets are the cost ceiling. |

Compatibility note: a companion is still a genuine DB-backed `Character` logged in through
`PlayerLoginService` (everything downstream — `PlayerStorage`, map/party packets,
`SessionCoordinator` — requires it). "Server-owned default character" means the row is
provisioned by the server from a template on a server-owned account, not borrowed from a
human player's account.

---

## 2. Architecture

```
 player: @agents find [role]
        │
        ▼
 AgentCompanionCommand  (rank 0, client/command/commands/gm0)
        │  offloaded via ThreadManager
        ▼
 AgentCompanionService ──────────────┐  validate caps/cooldown/map/eligibility
        │                            │  choose role (complement) + level (exact)
        ▼                            │
 AgentProvisioner (interface)        │  ephemeral only
        │                            │
        ▼                            │
 DbAgentProvisioner                  │  1. create server-owned account
        │                            │  2. CharacterFactoryRecipe (reuse!) → insertNewChar
        │                            │     level/job stats, AP/SP, skills, gear, potions, ammo
        │                            │  3. return ProvisionedAgent{charId, accountId, name}
        ▼                            │
 AgentManager.spawn(name, world, channel, LlmAgentBrain)
        │
        ▼
 CompanionPartyService ──────────────┘  auto-follow + party formation
        │
        ▼
 AgentSession (ephemeral flag → memory not persisted, despawn deletes)
```

### Key reuse

The repo already has a **server-side character creation pipeline**:

- `client/creator/CharacterFactoryRecipe` — level, job, str/dex/int/luk, AP/SP, HP/MP,
  meso, starting skills, starting equipment/items.
- `client/creator/veteran/*Creator` — per-job recipes (currently hard-coded level 30).
- `Character#insertNewChar(recipe)` — persists the row + keymap + inventory + skills +
  quest status, and returns the generated character id.
- `Character#canCreateChar(name)` / `Character#deleteCharFromDB(chr, accId)` /
  `Client#deleteCharacter(cid, accId)` — validation and the full delete cascade.

`DbAgentProvisioner` therefore does not invent a "fake character": it builds a
`CharacterFactoryRecipe` per (job, level) and calls the same `insertNewChar` a human
create-character request would.

---

## 3. Provisioning

### 3.1 Accounts

One account per live companion (v83 permits a single logged-in session per account, and
`SessionCoordinator.registerAgentClientIfAbsent` already atomically claims an account).

- `DbAgentProvisioner` inserts a server-owned account with a random name
  (`<LLM_AGENT_ACCOUNT_PREFIX><random>`, ≤13 chars), a random unusable password, and
  `characterslots = 1`.
- On dismiss it deletes the character (cascade) and then the account.
- Account name/prefix are configurable; accounts are never human-loginable (random secret).

### 3.2 Names

- Random name = `LLM_AGENT_NAME_PREFIX` + alphanumeric base, total ≤ 13 chars
  (`characters.name`/`accounts.name` are `varchar(13)`).
- **Note:** `Character#canCreateChar` enforces `[a-zA-Z0-9]{3,12}` and would reject the
  `[AI]` prefix. The provisioner validates the *base* name alphanumerically and prepends the
  prefix itself, guaranteeing uniqueness with a `SELECT ... WHERE name = ?` check.
- Transparency: the prefix is kept so players can tell companions from humans, and
  `LLM_AGENT_REQUIRE_PREFIX` semantics are unchanged for GM spawns.

### 3.3 Template

- Level = requester's level (exact) by default; `LLM_AGENT_LEVEL_MATCH` allows `BAND5`.
- Job/role = explicit `@agents find <role>`, else auto-complement the requester's job
  (warrior→cleric/healer, mage→warrior, archer→warrior, thief→warrior, pirate→warrior),
  else random.
- Stats/AP/SP/skills/gear/potions/ammo come from a per-(role, level-band) recipe table. First
  iteration reuses the existing creator recipe shapes scaled to the requested level band.
- Meso: a small fixed stipend (companions pay for potions); not player-transferable.

### 3.4 Deletion (ephemeral lifecycle)

- `AgentCompanionService.dismiss(player)` → `AgentManager.despawn(<name>)` (synchronous)
  → `AgentProvisioner.destroy(provisioned)`.
- `destroy` is best-effort: it must never throw, and it must remove the party membership
  (`Client#deleteCharacter` already calls `leaveParty`), the character cascade, and the
  account.
- Ephemeral sessions set an `ephemeral` flag so `AgentMemoryStore` persistence is skipped
  (in-memory memory only) — `AgentConfig.memoryPersist()` still governs persistent agents.

---

## 4. Player command: `@agents`

Rank 0 (`gm0`), registered in `CommandsExecutor`. Runs off the Netty loop via `ThreadManager`
(provisioning does DB I/O). Anonymous-GM rule: never block the event loop.

| Subcommand | Behaviour |
| --- | --- |
| `@agents list` | Show available roles and whether a companion can be summoned now (feature on, capacity, cooldown). |
| `@agents find [role]` | Validate → provision → spawn beside the player → follow → party. |
| `@agents dismiss` | Despawn + destroy the requester's companion(s). |
| `@agents pause` / `@agents resume` | Suspend/resume the requester's companion(s) without dismissing them (inbox preserved; GM `!agent pause\|resume` works for any agent). |

### `find` validation order

1. `USE_LLM_AGENTS` and `LLM_AGENT_PLAYER_SPAWN` enabled.
2. Player alive, on a map, not in trade / cash shop / event instance, not opted out.
3. Map is not an event/PvP map (`canSelfTransfer`-style guards).
4. Per-player cap (`LLM_AGENT_MAX_PER_PLAYER`, default 1) and request cooldown
   (`LLM_AGENT_REQUEST_COOLDOWN_MS`, default 60 s).
5. Global `LLM_AGENTS_MAX` respected; over capacity → clear denial (no silent failure).
6. Provision at the matched level/role; on failure, report and do not half-spawn.

### Auto-party

`CompanionPartyService` (seamed for tests):

- Party has room and the player is already in one → `Party.joinParty(agent, partyId, true)`.
- Player solo → `Party.createParty(player, true)` (player leader) then join the agent; if the
  player is below the party level gate (`USE_PARTY_FOR_STARTERS` off), skip the party and just
  follow.
- Failure to party is non-fatal: the companion still spawns and follows.

---

## 5. Fairness & abuse

- **No value transfer:** agents expose no trade/storage/mail/drop-to-player action; ephemeral
  characters and their mesos are destroyed on dismiss.
- **No AFK farming:** contributions require the requester on the same map and not idle
  (idle despawn, `LLM_AGENT_IDLE_DESPAWN_MS`); per-player cap and request cooldown; optional
  daily uptime cap. **Decision: the current posture is accepted** — per-player cap, request
  cooldown, no value transfer and no auto-loot are considered sufficient; idle despawn and
  uptime caps are intentionally not pursued.
- **No auto-loot for player companions** by default (agent-looted drops would be destroyed
  with the character). Persistent GM agents keep `LLM_AGENT_AUTO_LOOT` as configured.
- **Map/quest safety:** refuse event-instance/PvP maps; companions are companions-only and do
  not touch quest state.
- **Opt-out:** the requester implicitly opts in; if they are themselves opted out the command
  is refused. Other players' `agent_optout` continues to gate greetings/invites/whispers.
- **Cost:** the global LLM budget is the ceiling; over budget the brain fails safe (empty
  action list). A per-player request cooldown bounds request rate.

---

## 6. Config (new keys)

```
LLM_AGENT_PLAYER_SPAWN         false   # expose @agents to players
LLM_AGENT_MAX_PER_PLAYER       1
LLM_AGENT_REQUEST_COOLDOWN_MS  60000
LLM_AGENT_LEVEL_MATCH          EXACT   # EXACT | BAND5
LLM_AGENT_AUTO_PARTY           true
LLM_AGENT_COMPLEMENT_JOB       true
LLM_AGENT_ACCOUNT_PREFIX       "agnt"  # server-owned account name prefix
```

---

## 7. Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| **M5 — Provisioning** | `AgentProvisioner` + `DbAgentProvisioner` (account + recipe + delete), config, tests. | done |
| **M6 — Discovery/spawn** | `@agents list\|find\|dismiss`, `AgentCompanionService`, auto-follow, per-player caps/cooldown, tests. | done |
| **M7 — Auto-party** | `CompanionPartyService` (form/join), guard cases. | done |
| **M8 — Fairness/lifecycle** | idle despawn, daily uptime cap, metrics/audit, opt-out interplay. | not required (accepted posture) |
| **M9 — Quest/PQ audit** | Verify party-quest scripts tolerate companions; level-band/PQ eligibility rules; guide. | deferred |

### Definition of done (per repo process)

`mvn -q clean test` green; an independent verifier pass with HIGH/MEDIUM findings addressed;
`handoff` / `plan` / `guide` updated; committed with a `feat(agent):` message.

---

## 8. Deferred / open

- **Low-level companions (known behavior, accepted):** below level 10 a companion is a
  `BEGINNER` at the requester's level (basic attacks only), and auto-party is skipped because
  `Party.createParty` refuses sub-level-10 parties unless `USE_PARTY_FOR_STARTERS: true` — the
  companion still spawns and follows. (Also, an ephemeral companion's brain-emitted
  `PickupItem` actions are dropped as no-ops but still appear in the DEBUG action log.)
- ~~**Idle despawn & uptime caps** (M8)~~ — intentionally not pursued: the accepted anti-AFK
  posture is the per-player cap + request cooldown + no value transfer + no auto-loot. (The
  `Character` idle signal / reap hook would be needed if this is ever revisited.)
- **PQ script audit** (M9) — some v83 party-quest scripts assume human players; companions may
  break objective logic even though they can fill slots.
- **Persistent vs ephemeral toggle for GM spawns** — `!agent spawn` keeps the persistent
  character path; a future `!agent spawn --ephemeral` could unify both.
- **Recipe breadth** — **done.** `CompanionTemplate` builds a level-appropriate character:
  job advancement by tier (10/30/70/120), all AP spent, HP/MP per role, real skill builds per
  job tier (WZ-resolved, capped to `Skill#getMaxLevel()`, with a usable attack skill at every
  tier), and a data-driven level-appropriate weapon. Verified by a WZ-backed test.
- **Name prefix vs `canCreateChar`** — resolved by validating the base name and prepending the
  prefix server-side; revisit if the DB name column is ever widened.
- **`characters.name` has no UNIQUE index** — concurrent provisions rely on a
  `SELECT`-then-`INSERT` check (LOW, very unlikely with random names). A `UNIQUE KEY (name,
  world)` migration would close it.
- **Login-cache entry per ephemeral account** — `Server#deleteCharacterEntry` leaves an empty
  `accountChars` entry for the deleted character's account (LOW, pre-existing; not touched to
  avoid changing the human login-cache semantics).
- **DB-provisioning path is not unit-tested** — it needs a live MySQL; the service/policy and
  manager lifecycle are covered by fakes. A DB integration test is a follow-up.

---

## 9. Review history

- **Pass 1** (`5eddcd054`): independent verifier. Fixed HIGH `transferChannel` dropping the
  ephemeral/owner/provisioned state; MEDIUM per-player cap/cooldown TOCTOU (per-player
  request lock + concurrency test); MEDIUM provisioned-character leak after an exception;
  MEDIUM ephemeral rows surviving shutdown/GM despawn (AgentManager now deletes on despawn);
  MEDIUM account deleted when the character delete failed; MEDIUM companion auto-loot; LOW
  mid-transition guard. Added `AgentManagerCompanionLifecycleTest`.
- **Pass 2** (`07bd27510` → fixes in the follow-up commit): re-verified items 1–5 and 7 as
  correctly fixed. Found three more MEDIUMs, now fixed: (a) the auto-loot guard only covered the
  server-driven path — brain-driven `PickupItem` is now dropped for ephemeral sessions in
  `AgentActionDispatcher`; (b) `provision` held a pooled connection while `insertNewChar`/
  `loadAccountCharacters` opened their own, which could deadlock the small pool — the connection
  is now scoped tightly; (c) `AgentManager.despawn` ran the character-cascade delete while
  holding the global monitor — removal is now under the monitor and deletion happens outside it
  (`stop()` likewise). LOW: `requestLocks` became a fixed-size stripe array.
- **Pass 3** (follow-up commit): verified the loot gate, connection scoping, monitor discipline
  and stripe locks. Fixed two more MEDIUMs: the `insertNewChar`-false path could orphan the new
  account (now calls `rollbackProvision`), and moving the delete outside the monitor reopened a
  GM despawn→respawn race (a `pendingEphemeralDeletes` tombstone is now set under the monitor and
  checked by `spawn`, plus a `stopping` guard so a concurrent spawn cannot restart the ticker
  during `stop()`).
- **Merged engine workstreams** (same branch, after the companion review): hold-skill attack
  pacing + ranged cast stance (`AgentCombat`), foothold-aware platform stepping (`AgentMovement`),
  and player `@agents pause|resume` + GM `!agent pause|resume`. Their shared code is what
  companions run on, so the improvements apply to them automatically. A pre-existing
  order-dependent `MobSkillFactoryTest` assertion was made provider-independent (the factory's WZ
  provider is a JVM-wide static, so the test must not assume the temp test WZ won the race).
- **Companion templates** (`CompanionTemplate`): one verifier pass resolved every grant against
  the real WZ and found HIGH defects — `constants/skills/Warrior`/`Rogue` had stale first-job ids
  that resolved to `null`, leaving warriors with no attack skill and wasted SP. Fixed by correcting
  the constants (WZ-verified) and capping grants to `Skill#getMaxLevel()` with reconciled
  `remainingSp`; also fixed the healer tier progression (MAGICIAN→CLERIC→PRIEST→BISHOP), the
  thief dagger-vs-claw tiering, and the pirate fallback weapon. A WZ-backed test
  (`CompanionTemplateWzTest`) now rejects null/over-max grants and requires a usable attack skill
  per role × tier.
- **Names, cosmetics, movement & lifecycle** (later session): companions are now
  `Agent-<FunWord>` with randomized hair/face/skin + cash outfit (`CompanionAppearance`);
  movement uses client-captured stances (walk 2/3, stand 4/5, climb 16) with a normal walking
  pace and an idle-stance broadcast; `AgentManager.start()` reaps leftover `agnt%` accounts;
  secrets moved to env vars. Two notable bugs found and fixed: the companion cash-candidate
  scan was an inline O(n²) WZ scan that hung `@agents find` (now a cached, warmed, single-pass
  scan), and the live provisioning path NPE'd on `Client.createMock()`'s null channel (now
  guarded). `DbAgentProvisionerIT` covers the live path. **Still open:** the companion
  attack-animation `stance` (temporary `[ATKDBG]` capture is in `AbstractDealDamageHandler`).
