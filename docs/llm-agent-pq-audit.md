# M9 — Party-Quest Compatibility Audit for LLM Agent Companions

Milestone M9 of the companions plan (`docs/llm-agent-companions-plan.md`). **Read-only audit:**
no Java or script changes were made; no tests were added; nothing is committed.

**Question:** does an ephemeral agent companion filling a party slot break party-quest (PQ)
objective logic, and which PQs should allow companions?

**TL;DR answer:** Yes, it breaks most PQs. A companion is a real `Character` on the recruit
map, so `startInstance` registers and server-warps it into the instance like any member
(finding 1). Inside, it cannot progress: PQ stage portals are all scripted and the agent's
own travel guards refuse scripted portals and event instances (finding 2), and it cannot
talk to stage NPCs, hit reactors, or loot (finding 3). Every PQ whose stage logic iterates
*registered* players — pass counts, position combos, `isEventTeamTogether`, alive+in-area
checks — then deadlocks or is skewed (finding 4). Dismissing the companion mid-run (or a
channel transfer) fires the disconnect/leftParty hooks and **aborts the PQ for the remaining
humans** (finding 5). Entry gates skew in both directions: min-size gates pass with an agent
slot, exact-size gates and AmoriaPQ's job mask fail closed (finding 6).

**Recommended policy (details in §4):** companions must not enter scripted event instances at
all. Enforce it with a two-line guard in `EventInstanceManager.registerPlayer` +
`EventManager.getEligibleParty` — no allowlist, no per-PQ exceptions.

---

## 1. How a companion gets into a PQ today

A companion is an ephemeral, server-owned `Character` in the requester's party
(`CompanionPartyService`, `LLM_AGENT_AUTO_PARTY`), level-matched to the requester, that
follows the player across maps. There is **no gate that stops it entering a PQ with the
party** — `AgentCompanionService` only refuses *summoning* while the player is already in an
event (`AgentCompanionService.java:154-162`), which does not help an existing companion that
later walks to a recruit map. From there the normal PQ flow carries it in:

1. Leader talks to the entry NPC → `em.getEligibleParty(cm.getParty())`
   (`scripts/npc/9020000.js:91-99` pattern used by all PQ entry NPCs).
2. `em.startInstance(party, map)` → `eim.registerParty(party, map)`
   (`src/main/java/scripting/event/EventManager.java:561`).
3. `EventInstanceManager.registerParty` iterates eligible members and looks each up with
   `map.getCharacterById(mpc.getId())` (`EventInstanceManager.java:366`) — **the agent is a
   real logged-in `Character` on the recruit map, so it is registered.**
4. Each registered player's `playerEntry(eim, player)` runs `player.changeMap(entryMap)` —
   the agent is **server-warped into the PQ entry map** with everyone else.

Once inside, `AgentMovement.canSelfTransfer`/`canWarpTo` refuse event instances
(`AgentMovement.java:427-445`: *"never yank an agent into (or out of) an event/instance
map"*), so the agent cannot follow the party any further by warp; and `findPortalTo`
skips scripted portals (`AgentMovement.java:357-367`) — and every PQ stage transition
portal is scripted (`scripts/portal/kpq0-4.js`, `lpq0-7.js`, `party6_*.js` (Ellin),
`jnr*.js` (Magatia), `guild1F*.js` (GQ), …).

## 2. Cross-cutting findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **Companions are registered and warped into PQ instances automatically.** | `EventManager.startInstance` → `eim.registerParty` (`EventManager.java:561`); `EventInstanceManager.registerParty` uses `map.getCharacterById` (`EventInstanceManager.java:366`) which finds agents. |
| F2 | **Agents cannot progress between PQ stages.** All stage portals are scripted; `findPortalTo` skips `getScriptName() != null` portals and `warpTo`/`canSelfTransfer` refuse event instances. The agent strands on the first stage map of every multi-stage PQ. | `AgentMovement.java:357-367, 427-445`; portal scripts above. |
| F3 | **Agents cannot perform stage actions.** No NPC-dialog action exists in `AgentActionDispatcher#dispatch` (only chat/party/move/pickup/item/skill actions), reactors are only hit by attack packets targeting mobs, and ephemeral companions have auto-loot forced off (`AgentSession.java:370`). Passes, coupons, quiz answers, keys, seed deposits are all out of reach. | `AgentActionDispatcher.java:57-86`; `AgentSession.java:370`. |
| F4 | **Stage logic iterates registered players and counts the agent.** `eim.getPlayers()` / `getPlayerCount()` include the registered agent; `isEventTeamTogether()` requires *all* registered players on the same map; rect/area combo checks count the agent's (stranded or ground-level) position. | `EventInstanceManager.getPlayers/isEventTeamTogether`; `9020001.js:79-82,138`; `2040043.js:114-128`; `9103000.js:57`; `9201045.js:132-146`. |
| F5 | **Companion dismissal / channel transfer mid-PQ aborts the run for the humans.** Despawn force-disconnects; `Client#disconnectSession` calls `eim.playerDisconnected` (`Client.java:940-943`), and character deletion leaves the party (`Party.leaveParty` → `eim.leftParty`, `Party.java:421-426`). All standard PQ scripts implement both hooks as `if (eim.isEventTeamLackingNow(...)) end(eim)` — and `isEventTeamLackingNow` returns `getPlayerCount() <= minPlayers`, so any min-…-max party that included the agent collapses (e.g. a 4-member KPQ drops to 3 ≤ 3). The `end()` handler then warps the whole team out. | `Client.java:940-943`; `Party.java:421-426`; `EventInstanceManager.isEventTeamLackingNow`; `KerningPQ.js:158-175` (typical). |
| F6 | **Entry gates skew both ways.** `getEligibleParty` counts the agent (it is on the recruit map and level-matched), so (a) undermanned parties pass min-size gates and then hit the F2-F4 deadlocks; (b) exact-size PQs fail *closed* while a companion is in the party (RescueGaga 1/1, Magatia 4/4, Amoria 6/6, HorntailPQ 6/6, ElementalBattle 2/2 with fewer humans than the exact size); (c) AmoriaPQ additionally requires the party job-niche mask `mask == 3` (all warrior/beginner) — a complement-job companion (healer-first, *never* the requester's class) makes entry impossible for a warrior requester. | `scripts/event/AmoriaPQ.js:120`; `scripts/event/EllinPQ.js:91` (per-member adventurer gate); `scripts/event/*.js` `getEligibleParty`. |
| F7 | **Rewards.** Stage EXP/meso (`giveEventPlayersExp/Meso`) and `setEventCleared` quest points hit every registered player, including the agent — accepted by the companions plan (§2 decision 3: "exp/meso are shared normally"); agents are ephemeral and value-transfer to the player is impossible. Random item rewards are granted per *talking* player (`eim.giveEventReward(cm.getPlayer())`), which agents cannot do — no distribution skew, no reward stealing. Exclusive PQ items are removed from a leaving player's inventory (`dropExclusiveItems`) — a no-op for a deleted agent. | `EventInstanceManager.giveEventPlayersExp/setEventCleared/giveEventReward`; `2013002.js:58`. |

### Checklist answers (per the audit brief)

1. **Entry gates** — broken (F6): min-size gates pass that shouldn't; exact-size and job-mask
   gates fail closed while a companion is present. Leader and level checks behave correctly
   (companion level = requester level, player stays leader).
2. **"All members in map / on portal" checks** — broken (F4): any check iterating
   *registered* players (`getPlayers`, `getPlayerCount`, `isEventTeamTogether`) counts an
   agent that cannot comply. Map-scoped checks (`getMap().getPlayers()`,
   `getMap().getNumPlayersInArea()`) fail open — they simply don't see the stranded agent.
3. **Objective logic** — broken (F3, F4): pass counts sized by `getPlayerCount()`, position
   combos, alive+in-area checks, per-room exploration locks, and key/item collection all
   either deadlock or are skewed by the agent.
4. **Stuck / despawned agents** — guaranteed stuck after the first scripted portal (F2); the
   warp guard correctly refuses to re-enter, which makes the stranding *permanent*. A despawn
   mid-run is handled only via the logout path (`playerDisconnected`) plus
   `deleteCharacter`→`leaveParty`→`leftParty` — and in every PQ script those abort the run
   for everyone (F5). There is no path where a registered `Character` disappears *without*
   those hooks (despawn always force-disconnects first), but the hooks' scripts are written
   assuming a *human* who chose to leave, not a disposable follower.
5. **Rewards** — no exploitable skew (F7). EXP/meso/quest-point share is accepted by design.

## 3. Per-PQ verdicts

Verdicts: **UNSAFE** = companion presence can deadlock or abort the PQ → **block**;
**CAUTION** = mechanically compatible but the agent strands, contributes little, and its
despawn still aborts min-size runs → **block by default**; **SAFE** = the companion never
gets registered/inside or has nothing to break → allow. "size" = min-max eligible players.

| PQ | Script(s) | size / level | Verdict | Concrete justification | Policy |
|---|---|---|---|---|---|
| Kerning PQ | `scripts/event/KerningPQ.js`, `scripts/npc/9020001.js` | 3-4 / 21-30 | **UNSAFE** | Stage 1 needs `numpasses = eim.getPlayerCount() - 1` passes (`9020001.js:138`) — the agent can't answer NPC quizzes or loot; a 3-party with agent can never field the passes. Stages 2-4 require exactly 3 members on ropes/platforms/barrels (`rectangleStages`, `9020001.js:79-82` iterates `eim.getPlayers()` positions) — impossible when one "member" can't climb. | block |
| Ludibrium PQ | `scripts/event/LudiPQ.js`, `scripts/npc/2040034/35/2040043.js` | 5-6 / 35-50 | **UNSAFE** | Stage 8: `party = cm.getEventInstance().getPlayers()` … `playersOnCombo == 5` (`2040043.js:116-128`) — a 5-party with agent maxes at 4 → deadlock; at 6 the agent's position skews the box combo if it wanders into an area. Earlier stages are pass/quiz/item-collection (agent can't). | block |
| Ludibrium Maze PQ | `scripts/event/LudiMazePQ.js`, `scripts/npc/9103000.js` | 3-6 / 51-70 | **UNSAFE** | Members are scattered into random maze maps (`playerEntry` uses `Math.random()*15`); the clear NPC requires `eim.isEventTeamTogether()` (`9103000.js:57`). The agent can't traverse scripted maze portals → stranded → PQ can never be cleared. | block |
| Orbis PQ | `scripts/event/OrbisPQ.js`, `scripts/npc/2013001.js` | 5-6 / 51-70 | **UNSAFE** (borderline) | Stage 4 needs "exactly 3 players on these platforms" via `getNumPlayersInArea` (map-scoped, so *stranded* agents are tolerated), but the tower is 8+ scripted-portal stages the agent can't traverse, and its despawn mid-run ends the run (`isEventTeamLackingNow`). Item stages (30/40 pieces) need looting the agent lacks. | block |
| Henesys PQ | `scripts/event/HenesysPQ.js`, NPC `1012112.js` | 3-6 / 10+ | **CAUTION** | Single main map (protect bunny → seeds via drop-on-reactor, humans only) — agent can genuinely help fight. Still: despawn aborts min-size runs; bonus map transition strands it. | block (safe to allow with guard if desired) |
| Ellin PQ | `scripts/event/EllinPQ.js`, `scripts/npc/2133000.js`, `scripts/portal/party6_*.js` | 4-6 / 44-55 | **UNSAFE** | Per-member adventurer gate (`EllinPQ.js:91`); puzzle/position stages behind scripted portals (`party6_*.js`); fragment rewards via portal script (`party6_stage800.js`) the agent can't use; despawn aborts. | block |
| Magatia PQ (Romeo & Juliet) | `scripts/event/MagatiaPQ_A.js`, `MagatiaPQ_Z.js`, `scripts/npc/21120*.js`, `scripts/portal/jnr*.js` | 4-4 (exact) / 71-85 | **UNSAFE** | Exact 4/4 entry: 2-3 humans + agent can't start; 3 humans + agent starts but the 4-room split (`jnr4_r*.js` locks each room after one entry), quiz NPC stages and the multi-room Yulete sequence assume 4 acting members — the agent can do none of them. | block |
| Amoria PQ | `scripts/event/AmoriaPQ.js`, `scripts/npc/9201044/45.js` | 6-6 (exact) / 40+ | **UNSAFE** | Entry requires party job mask `mask == 3` — all warrior/beginner (`AmoriaPQ.js:120`); the complement-job companion (healer/mage) breaks entry. Inside: `playersOnCombo == 5` (`9201044.js:203`) and stage 5 requires `isEventTeamTogether()` **and** every registered player alive inside an area (`9201045.js:132-146`) — a dead/stranded agent blocks forever. | block |
| Pirate PQ (Lord Pirate) | `scripts/event/PiratePQ.js`, `scripts/npc/2094000.js` | 3-6 / 55-100 | **CAUTION** | Kill/progress stages + boss; agent can fight. Chests are reactor-based (agent can't), stage portals scripted (agent strands), despawn aborts min runs. | block (allowable with guard) |
| Holiday PQ 1/2/3 | `scripts/event/HolidayPQ_{1,2,3}.js`, `scripts/npc/9105004.js` | 3-6 / 21-50 | **CAUTION** | Single-map snowman defense; leader-only NPC staging; agent fights minions/boss. Snow-Vigor deposits are item-drops (human-only). Despawn aborts min-size runs. | block (safest); acceptable with guard |
| Cafe PQ 1-6 | `scripts/event/CafePQ_{1..6}.js` | 3-6 / 21-120 | **CAUTION** | 3 map event chain (`eventMaps = [190000000,190000001,190000002]`) with kill-based progress; same stranding/despawn caveats; mechanics otherwise member-agnostic. | block (allowable with guard) |
| El Nath PQ | `scripts/event/ElnathPQ.js` | 1-4 / 80+ | **CAUTION** | Single-map instance (`entryMap 921100300`); no registered-player iteration found; despawn aborts runs at min size. | block (allowable with guard) |
| Treasure PQ | `scripts/event/TreasurePQ.js`, `scripts/npc/9220019.js` | 4-6 / 140+ | **CAUTION** | Multi-map ninja-castle gauntlet behind scripted portals (agent strands); final `warpEventTeam` covers the agent. No per-member item counts found. | block |
| Boss Rush PQ | `scripts/event/BossRushPQ.js`, `scripts/npc/9000037.js` | 1-6 / 1+ | **CAUTION** | Single-map boss gauntlet; final reward/exit NPC requires `isEventTeamTogether()` (`9000037.js:105`) — a stranded or despawned agent blocks the final warp. | block |
| Zakum PQ (jump quest) | `scripts/event/ZakumPQ.js`, `scripts/npc/2030008.js` | 1-6 / 50+ | **CAUTION** | Per-member documents from the jump quest — the agent cannot ascend (movement never climbs) so it can't contribute, but it doesn't block others (item-based, not player-count-based). It strands in the mine until `end()` warps it out. | block (no benefit) |
| Horntail PQ | `scripts/event/HorntailPQ.js`, `scripts/npc/2083001.js` | 6-6 (exact) / 120+ | **UNSAFE** | Exact 6 entry; key collection `4001087-4001093` handed to the inner NPC (`2083001.js:102-124`) — the agent can't loot or NPC-talk; a 5-human+agent party can't field all keys. | block |
| Guild Quest (Sharenian) | `scripts/event/GuildQuest.js`, `scripts/npc/9040000.js`, `scripts/portal/guild1F*.js` | 6-30 / 1+ | **UNSAFE** | Room/portal logic keyed by `eim.gridCheck` per member (`guild1F00.js:31`); multi-stage puzzles + guild registration; agents can't participate in any stage interaction. | block |
| Mu Lung Training Center (TD_Battle 1-5) | `scripts/event/TD_Battle{1..5}.js` | 2-6 / 70+ | **CAUTION** | Single-map boss battle; no member-iteration found; agent can fight. Despawn aborts a 2-member run (`getPlayerCount() <= minPlayers`). | block (allowable with guard) |
| Balrog Battle (+ Easy) | `scripts/event/BalrogBattle.js`, `BalrogBattle_Easy.js` | 6-30 / 3-30, 50+ | **CAUTION** | Single-map cabin battle; agent can fight; despawn/stranding caveats as above. | block (allowable with guard) |
| Single-map boss battles (DelliBattle, LatanicaBattle, MK_PrimeMinister{,2}, PapulatusBattle, ElementalBattle, KingPepeAndYetis, MahaBattle, NineSpirit, TD-family) | `scripts/event/*.js` | various | **CAUTION** | No stage iteration; agent fights and is warped out with the team by `end()`. Exact-size ones (ElementalBattle 2/2) let a 1-human+agent pair "start" — a gate-skew smell. | block (allowable with guard) |
| Monster Carnival (CPQ) | `scripts/npc/2042000.js`, `cpqchallenge*.js`, `server/partyquest/MonsterCarnival.java` | 2-6 vs equal | **UNSAFE** | Party-vs-party matching requires **equal** `getMembers().size()` (`cpqchallenge.js:40,55`) — an agent inflates one side's size and distorts matchmaking; battle maps are behind scripted portals (agent strands in lobby). | block |
| Ariant Coliseum | `server/partyquest/AriantColiseum.java` (individual entry) | solo | **SAFE** | Individual competition; the agent cannot register (no NPC interaction) and can't reach the arena (scripted entry). | allow (moot) |
| Rescue Gaga | `scripts/event/RescueGaga.js` | 1-1 / 12+ | **SAFE (blocks entry)** | `maxPlayers = 1`: any party containing the companion yields `eligible = []` → the player **cannot start** until they dismiss it. Fails closed — annoying but not corrupting. | block (entry-fail-closed) |
| Expedition bosses (ZakumBattle, HorntailBattle, PinkBeanBattle, ScargaBattle, ShowaBattle, CWKPQ, GuardianNex) | `scripts/event/*.js` (expedition-based) | 6-30 exped | **SAFE** | Registration is expedition-roster based, not party-based (`registerExpeditionTeam`); the companion is never registered and `canWarpTo` refuses instance maps — it simply waits outside. | allow (moot) |
| Transport events (Subway, KerningTrain, Elevator, Cabin, Boats, Trains, Hak, Genie, AirPlane, 2xEvent, s4aWorld) | `scripts/event/{Subway,KerningTrain,Elevator,...}.js` | n/a | **N/A** | Not PQs (commuter/ride events). `warpEveryone` moves the whole waiting room; the companion just rides along. No PQ logic to break. | allow |
| Template / misc | `scripts/event/0_EXAMPLE.js`, `q3239.js` | n/a | **N/A** | Template / non-PQ. | n/a |

*(Ellin PQ's per-map portals live under the `party6_*` name family — maps 930000xxx —
confirmed via `party6_stage800.js` warping to 300030100 and awarding Ellin fragments.)*

## 4. Recommended policy & minimal guard

### Policy

**Default: companions are blocked from all scripted event instances** — no allowlist. Two
reasons the "allow the safe ones" approach loses:

1. Even the mechanically compatible PQs (TD_Battle, HolidayPQ, boss battles) share the F5
   abort: at minimum party size, `isEventTeamLackingNow` → `getPlayerCount() <= minPlayers`
   makes *any* companion dismissal, channel transfer or despawn end the run for the humans.
2. An allowlist is a maintenance burden on a script set that is content, not code — new/
   edited PQs would silently re-open the holes.

Companions remain useful everywhere else (training, party search, boss *queues* outside
instances). "Allow-with-guard" (dismiss-on-enter) is not proposed: it would delete a
companion the player did not choose to lose.

### Minimal code guard (IMPLEMENTED 2026-10-18)

Two surgical changes, both server-side, no script edits — now applied and covered by tests
(`AgentManagerEphemeralGuardTest`, `EventEphemeralAgentFilterTest`):

1. **Never register ephemeral companions in event instances.**
   - File: `src/main/java/scripting/event/EventInstanceManager.java`
   - Approach: at the top of `registerPlayer(Character, boolean)`, return early if the
     character is an ephemeral companion. Detection: add
     `public boolean isEphemeralAgent(int characterId)` to `AgentManager` (it already owns
     the ephemeral sessions / `AgentSession#isEphemeral`, `AgentSession.java:127`) and call
     it from `EventInstanceManager` (a plain static lookup, no new state). A
     name/account-prefix check (`LLM_AGENT_ACCOUNT_PREFIX` = `agnt`) would also work but is
     stringly.
   - Effect: kills F1, F2, F4 and F5 in one move — the agent is never warped in, never
     counted by `getPlayers()`/`getPlayerCount()`/`isEventTeamTogether()`, and its despawn
     can no longer abort anyone's PQ. It remains in the party and outside the instance.
2. **Exclude ephemeral companions from PQ eligibility counts.**
   - File: `src/main/java/scripting/event/EventManager.java` (`getEligibleParty`, line 722)
     — filter the script-supplied `PartyCharacter[]` (or filter in
     `Party#getPartyMembersOnline`) so agents don't satisfy `minPlayers`/`maxPlayers` gates.
   - Without this, a player+agent duo passes a min-3 gate (finding F6a) and then the
     instance starts underfilled; with it, PQs again require the intended number of *real*
     players, and exact-size PQs (Magatia 4/4, Amoria 6/6, HorntailPQ 6/6) behave exactly
     as before companions existed. AmoriaPQ's `mask == 3` job gate also self-heals.

Net effect: companions can no longer enter, block, deadlock, or abort any PQ, and can no
longer game party-size gates — while remaining fully functional in the overworld.

### Implementation notes (2026-10-18, post verifier pass)

- **CPQ needed its own guard (verifier HIGH-2).** Monster Carnival bypasses
  `EventInstanceManager` entirely (the battle map is a disposable map with no eim, and
  `MonsterCarnival` iterates raw party rosters). Closed server-side:
  `isCPQParty` skips ephemeral agents, `canStartCPQ` rejects (±3) any party containing one,
  `startCPQ`/`startCPQ2` message both leaders and warp the lobby out, and `MonsterCarnival`'s
  constructor entry loops skip companions as defense in depth.
- **Despawn/registration TOCTOU (verifier MEDIUM-1):** the `registerPlayer` guard now runs
  under the eim writeLock, narrowing the race with a concurrent despawn/channel transfer.
  A tiny window remains (check-then-act across the two locks); it mirrors the pre-existing
  race for human players.
- **Persistent (operator-spawned) agents remain PQ-eligible** (verifier LOW-1). That is
  deliberate: persistent agents are operator-controlled experiments, not player-facing
  content. If operators spawn them into PQ parties, the §2 findings apply to them.
- **Latent, no current impact (verifier LOW-2/3):** `warpParty` iterates unfiltered rosters
  (no active script warps a party into an instance map), and PyramidPQ's dead entry gate
  counts raw members. No action taken.
- The verifier's HIGH-1 (leftover `[ATTACKDBG]` println in `PacketCreator.addAttackBody`)
  was animation-session temp logging, reverted before commit — not part of this change.

### What was NOT done in the audit itself

- No JS scripts changed; no docs other than this file; nothing committed by the audit.
- `mvn compile` was not run during the audit (pure analysis; the build was in use by another
  agent). The guard implementation was built and tested separately (full suite green).
