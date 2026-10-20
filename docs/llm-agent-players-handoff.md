# LLM Agent Players — Developer Handoff

Audience: a fresh engineer or AI agent picking this work up cold.
Read this first, then `docs/llm-agent-players-plan.md` (the full design/plan).
For a player/operator-facing walkthrough, see `docs/llm-agent-players-guide.md`.

---

## 0. TL;DR

Cosmic (MapleStory v83 server emulator, Java 21 / Maven / Netty) now supports
**in-process, connection-less "agent players"** driven by an OpenAI-compatible LLM.
An agent is a real `Character` logged in through the same bootstrap a human uses, whose
inbound actions are synthesized packets dispatched through the **real handlers**, and
whose perception is a mix of explicit event hooks and a range-limited map snapshot.

- **Branch:** `feature/llm-agent-players`. **Not merged to `master`** — a PR/merge is the final
  release step.
- **HEAD:** `c972f78fa` (§11.1 closed; Tier-2 pathfinding landed 2026-10-20, §4.20; harness
  rounds 6–8 live-verified PQ completions (KPQ/HPQ/LPQ/Ellin), interaction/social actions and
  the guild/marriage/family flows; full history in §10).
- **Status:** engine M0–M4 + player opt-out + player-facing **ephemeral companions** M5–M7 are
  shipped and independently verified. M8 (idle despawn / uptime caps) is intentionally not
  pursued; **M9 (party-quest compatibility audit) is closed** — companions are blocked from all
  scripted event instances and CPQ (`docs/llm-agent-pq-audit.md`). **§11.1 open work is fully
  closed** (pathing depth, test gaps, per-weapon animation). **Animation fully verified:**
  magic (Fire Arrow), melee (Fighter `CLOSE_RANGE_ATTACK`) and ranged (Assassin `RANGED_ATTACK`
  + Lucky Seven) all render on a real client with the `22 | facing` direction byte; only
  per-weapon booster-adjusted speed is accepted as out of scope.
- **Player commands (this server's headings):** players use **`@`** (`@agents list|find|pause|
  resume|dismiss`, `@agentoptout`); GMs use **`!`** (`!agent spawn|…`). **`/…` is not a command
  heading here** — a `/…` line from a non-GM is silently dropped.
- **What a summoned companion is:** an ephemeral, server-owned character named `Agent-<FunWord>`
  with a random hair/face/skin and cash outfit, built at the player's level with a real
  job/skills/gear (`CompanionTemplate`), auto-following and auto-partying (level ≥ 10), deleted
  on dismiss/GM-despawn/shutdown (plus a startup reaper for anything left behind).
- **Defaults:** source ships off (`USE_LLM_AGENTS: false`), but the committed `config.yaml` has
  the starter profile on (`USE_LLM_AGENTS: true`, auto-* on, `LLM_AGENT_PLAYER_SPAWN: true`).
  **Secrets are read from the environment** (`LLM_API_URL`/`LLM_API_KEY`/`LLM_MODEL`,
  `DB_USER`/`DB_PASS`); `.env` is gitignored and `config.yaml` has blank placeholders.
- **Build:** `mvn -q clean test` (green). Fast loop: `mvn -q -Dtest='net.server.agent.*Test' test`.
- **Client animation (attacks/casts):** the attack packet's `direction` byte must mirror a real
  client — an **action value with the facing in the low bit** (`22` facing right, `23` facing
  left), **not** the raw facing (`0`/`1`). The client silently refuses to animate a cast whose
  `direction` byte is `0`/`1`, so agents used to swing invisibly while mobs still took damage.
  `stance` stays `0`, `speed` `6`, `display` `0`. Confirmed live against a real client: a mage
  companion's **Fire Arrow** renders after this fix. Mirrored in `AgentCombat` (attacks) and
  `AgentSkill` (buffs). See §4.25.
- **Combat skill choice:** agents auto-select the strongest learned class attack skill when
  `LLM_AGENT_ATTACK_SKILL` is `0` (`AgentCombat.selectAttackSkill`), falling back to a basic
  attack; a non-zero value still forces that skill. This is what makes mixed-class companions
  actually cast instead of always basic-attacking.
- **Client animation (movement):** companion walk/stand/climb stances (`2`/`3`, `4`/`5`, `16`)
  were captured from a real client and are mirrored in `AgentMovement`. Do not regress them
  without a client check (see §4.25).

---

## 1. Build, test, run

```bash
# from repo root
mvn -q -DskipTests compile        # fast compile
mvn -q clean test                 # full suite (includes net.server.agent.*)
mvn -q -Dtest='net.server.agent.*Test' test
```

Running the server (see `README.md` for full setup):
1. MySQL 8 + run `database/sql/1..4` then **`database/sql/migration/v0.15.0.sql`**
   (`agent_memory`) and **`database/sql/migration/v0.16.0.sql`** (`agent_optout`). Agents still
   work without them; memory/opt-out persistence just logs warnings.
2. Set `DB_PASS` (and optionally LLM keys) in `config.yaml`.
3. `net.server.Server#main` (or `docker compose up --build`).

Exercise agents in-game:
1. `USE_LLM_AGENTS: true`; set `LLM_API_URL` + `LLM_MODEL` (works with OpenAI/Azure/Ollama/
   llama.cpp/vLLM). Leave URL blank to use the deterministic `ScriptedAgentBrain`.
2. Create/choose a character in world 0 (name it with the `[AI]` prefix by convention).
3. Log in as a GM (rank 5+) and run:
   - `!agent spawn <name>` — brings the character online as an agent
   - `!agent list`
   - `!agent say <name> <text>` — GM instruction to the brain
   - `!agent follow <name> <playerName>` / `!agent stopfollow <name>`
   - `!agent search <name>` — register as a party-search leader (solos can find it)
   - `!agent despawn <name>`

---

## 2. Architecture

```
                    ┌──────────────────────────────────────────────┐
                    │                 AgentManager                 │  net/server!agent/AgentManager.java
                    │  registry · ticker · brain pool · event路由  │
                    └───────────────┬──────────────────────────────┘
                                    │ owns 1..N
                    ┌───────────────▼──────────────────────────────┐
                    │                 AgentSession                  │
                    │  inbox(AgentEvent) · deciding CAS · actionExec│
                    │  rate limiter · follow target                 │
                    └───────┬───────────────────────▲──────────────┘
             decide()       │                       │ enqueue(event)
                    ┌───────▼──────┐        ┌───────┴────────┐
                    │  AgentBrain  │        │  AgentEventBus │  (implicit: AgentManager hook methods)
                    │ Llm / Script │        └───────▲────────┘
                    └───────┬──────┘                │ hooks in
                            │ List<AgentAction>      │ chat/party/whisper/map
                    ┌───────▼────────────────────────┴──────────────┐
                    │           AgentActionDispatcher                │
                    │  synthesizes InPacket → real PacketProcessor   │
                    └───────┬───────────────────────────────────────┘
                            │
                    ┌───────▼───────────────────────────────────────┐
                    │  AgentClient extends Client (no socket)        │
                    │  + Character / Channel / World / MapleMap      │
                    └───────────────────────────────────────────────┘
```

**Outbound ("what the agent is told"):** `AgentClient` overrides `Client#writePacket`,
so all packets the server would send are captured/dropped instead of hitting a socket.
Currently packets are dropped; the seam exists for a future faithful-transcript feature.

**Inbound ("what the agent does"):** `AgentSession#dispatchInbound(RecvOpcode, InPacket)`
looks up the handler from the agent's `PacketProcessor` and calls it directly (after
`validateState`). This means normal validation/spam-guards apply.

**Perception:** `AgentPerception.observe(Character)` builds an `AgentObservation`
(players/monsters/items/NPCs in range, party, own stats). Chat/party/whisper/map events
arrive via lightweight hooks (see §5).

**Threading:**
- Decisions (possibly blocking LLM calls) run on `AgentManager`'s fixed `brainPool`
  (size `LLM_MAX_CONCURRENT_CALLS`). **Never** on a Netty event loop.
- Each `AgentSession` owns a single-thread `actionExecutor`; all game mutations for that
  agent happen there, serialized.
- `AgentManager` ticker (single thread) calls `AgentSession#tick` every `LLM_AGENT_TICK_MS`.
- `spawn` is `synchronized` on `AgentManager`; `despawn` uses the concurrent map's atomic
  remove and does its DB I/O outside any monitor. `!agent` runs on `ThreadManager`.

---

## 3. File map

### New (all under `src/main/java/net/server!agent/`)
| File | Responsibility |
| --- | --- |
| `AgentManager` | Singleton: start/stop, spawn/despawn, event routing hooks, party-search + follow control, ticker + brain pool. |
| `AgentSession` | One agent: inbox, decide cycle, action execution, follow, rate limit. |
| `AgentClient` | `Client` subclass with no socket; overrides `writePacket`. |
| `AgentAction` | Sealed interface of actions (`Say`, `PartySay`, `Whisper`, `InviteToParty`, `Accept/DenyPartyInvite`, `LeaveParty`, `MoveTo`, `PickupItem`, `UseItem`, `Respawn`, `Attack`, `SkillAttack`, `SkillAttackMany`, `RechargeAmmo`, `CastBuff`, `CastHeal`, `Remember`, `Idle`). |
| `AgentActionDispatcher` | Maps actions → synthetic inbound packets; content filter; audit log; rate-limit gate. |
| `AgentEvent` | Sealed interface of perceived events (`MapChat`, `PartyChat`, `Whisper`, `PartyInvite`, `PartyUpdate`, `MapChanged`, `Tick`, `Instruction`). |
| `AgentBrain` | Decision interface: `List<AgentAction> decide(observation, events, memory)`. |
| `LlmAgentBrain` | LLM implementation via tool calling; the brain itself only reacts to given events (proactive behavior lives in `AgentProactivity`); fails safe (empty list). |
| `OpenAiChatClient` | Java 21 `HttpClient` + Jackson OpenAI chat-completions client + tool schema. |
| `ScriptedAgentBrain` | Deterministic brain (no LLM); used by tests and when no LLM is configured. |
| `AgentBrainFactory` | Chooses LLM vs scripted; injects persona system prompt. |
| `AgentObservation` | Immutable perception snapshot (records + nested observed entity records). |
| `AgentPerception` | Builds `AgentObservation` (BOUNDED range or OMNISCIENT). |
| `AgentMovement` | Synthesizes clamped `MOVE_PLAYER` packets (absolute fragment + real jump fragment, destination fh id, v83-derived stances); `stepToward`; snap-to-surface invariant + limbo recovery; `canWarpTo`/`warpTo` for cross-map follow. |
| `AgentPhysics` | Real v83 movement physics read from `Physics.img.xml` (gravity 2000, jump 555, walk 125, fall 670) + jump-arc simulation with platform-clip rejection. |
| `AgentNavGraph` | Per-map cached stand-point navigation graph (walk/weld/fall/validated-jump/climb edges, async-warmed like the ladder cache) + A* routing. |
| `AgentMovementState` | Per-session manoeuvre state (route under execution); invalidated on new target, map change or stall. |
| `AgentCombat` | Potion self-sustain, respawn, auto-looting, ammo perception/resupply, and melee/magic/ranged basic + skill attacks (packet builders + damage/ammo/job gating + multi-target + multi-line + facing). |
| `AgentProactivity` | Opt-in proactive behaviors: greet nearby players, party-search matchmaking, patrol (all gated by `LLM_AGENT_PROACTIVE`). |
| `AgentSkill` | Buff-skill casting (0-target attack packet + `StatEffect` application) and heal-skill casting (`SPECIAL_MOVE` + party heal). |
| `AgentPathing` | Foothold + rope/ladder pathfinder: routes from the cached `AgentNavGraph` (A*) with a legacy bounded-BFS fallback (rope starts, warming, oversized maps), and one-clamped-step-per-tick route execution when the direct step stalls. |
| `AgentMemory` | Per-agent facts + rolling dialogue (synchronized). |
| `AgentMemoryStore` | JDBC persistence for `AgentMemory` (`agent_memory` table). |
| `AgentOptoutStore` | JDBC persistence for player opt-out (`agent_optout` table); loaded into `AgentManager` at start. |
| `AgentPersona` | Persona record + JSON loader. |
| `AgentPersonaStore` | Cached JSON/YAML persona file loading (case-insensitive lookup). |
| `AgentConfig` | Typed access to `config.yaml` with safe defaults. |
| `AgentProvisioner` | Interface + `ProvisionedAgent` record for server-owned ephemeral character provisioning. |
| `DbAgentProvisioner` | JDBC implementation: dedicated account + recipe-built character; deletes both on despawn. |
| `AgentCompanionService` | Player companion policy/orchestration (`@agents find|dismiss|list`), per-player caps/cooldown. |
| `CompanionPartyService` | Forms/extend a party so a summoned companion joins the requester. |
| `AgentRole` | Canonical companion roles → base job, complement policy (any *other* class; healer-first weighted), aliases. |
| `CompanionTemplate` | Level-appropriate build: advanced job tier, AP/HP/MP, WZ-resolved skill build, gear. |
| `CompanionAppearance` | Random hair/face/skin + cash outfit; cached, warmed-off-path WZ candidate scan. |

### New (elsewhere)
- `src/main/java/net/server/PlayerLoginService.java` — channel bootstrap **extracted** from
  `PlayerLoggedinHandler`; used by both real logins and agent spawn (behavior-preserving).
- `src/main/java/client/command/commands/gm5/AgentCommand.java` — `!agent ...`.
- `src/main/java/client/command/commands/gm0/AgentCompanionCommand.java` — player `@agents ...`.
- `database/sql/migration/v0.15.0.sql` — `agent_memory` table.
- `database/sql/migration/v0.16.0.sql` — `agent_optout` table.
- `src/main/java/client/command/commands/gm0/AgentOptoutCommand.java` — player `@agentoptout`.
- `src/test/java/net/server!agent/*` — 23 test classes (85 tests), plus the M9 guard, pathing
  (`AgentMovementPathingTest`, incl. a real-`Map.wz` rope-route falsification) and test-gap
  suites (`AgentCombatAmmoShopTest`, `AgentManagerOptoutGateTest`,
  `AgentManagerTransferChannelTest`) — 2173 tests as of 2026-10-19, 2286 as of 2026-10-20
  (incl. `AgentPathingTier2Test`, §4.20).
- `docs/llm-agent-players-plan.md`, this file.

### Modified (small, surgical)
- `client/Client.java` — added overridable `protected void writePacket(Packet)` (default still
  `ioChannel.writeAndFlush`), null-safe `closeSession`/`disconnectSession`/idle check,
  `getPacketProcessor()`.
- `client/Character.java` — `changeMapInternal` calls `AgentManager.onMapChanged`.
- `net/server/Server.java` — start/stop `AgentManager` when `AgentConfig.enabled()`;
  `getChannelDependencies()`.
- `net/server/world/World.java` — `partyChat` → `onPartyChat`; `updateParty(int,…)` →
  `onPartyUpdate` (events) + `refreshAutoFollow()` (post-mutation).
- `net/server/channel/handlers/{GeneralChatHandler,WhisperHandler,PartyOperationHandler}.java`
  — event hooks.
- `net/server/coordinator/session/SessionCoordinator.java` — `isAccountOnline(int)`,
  `registerAgentClientIfAbsent(Client)` (atomic agent account claim); `onlineClients` is a
  `ConcurrentHashMap` and `updateOnlineClient` no longer disconnects the client it just stored.
- `server/StatEffect.java` — `hasDamage()` (whether WZ carried a `damage` node) so agents do
  not treat utility skills as attacks.
- `client/inventory/Inventory.java` — added `listCopy()`, a lock-protected inventory snapshot
  so perception can read the USE inventory off the brain thread without racing the action
  executor.
- `config/ServerConfig.java` + `config.yaml` — agent config keys.
- `scripting/event/EventInstanceManager.java`, `scripting/event/EventManager.java`,
  `scripting/npc/NPCConversationManager.java`, `server/partyquest/MonsterCarnival.java` —
  M9: ephemeral companions are kept out of scripted event instances and the Monster Carnival
  (see `docs/llm-agent-pq-audit.md`).
- `pom.xml` — `jackson-databind:2.17.2`.

---

## 4. Invariants & gotchas (do not break these)

1. **`PlayerLoginService.finishLogin` is a byte-for-byte extraction** of the old
   `PlayerLoggedinHandler` body. If you change login behavior, change it there.
2. **Never block a Netty event loop.** Agent work runs on `brainPool`/`actionExecutor`/
   `ThreadManager`. `!agent` is offloaded via `ThreadManager`.
3. **All agent chat goes through `sanitize()`**, which clamps to 127 chars and strips
   leading `/ @ !` — otherwise the agent can execute server commands or self-disconnect
   (autoban on >127-char chat). Do not bypass.
4. **Never spawn an agent whose character/account is already online.** `spawn` checks
   `PlayerStorage` + `SessionCoordinator.isAccountOnline`, then atomically claims the account
   with `registerAgentClientIfAbsent` after the (slow) DB load. Without that atomic claim a
   real player logging in concurrently would be force-disconnected by `updateLoginState`.
   Never let the agent be the one that kicks a real session.
5. **Despawn is synchronous** (`forceDisconnect`) to avoid a despawn→respawn race that
   deleted the new agent.
6. **Movement is clamped** to 16-bit + map area and capped at `MAX_STEP` (150px) per action —
   no teleport from hallucinated coordinates.
7. **Item pickup validates** the handler's range box (`|dx|≤800`, `|dy|≤600`) and ownership.
8. **Follow state is recomputed after party mutations** (`refreshAutoFollow`), and a manual
   `!agent follow` is sticky via `AgentSession.isManualFollow()`.
9. **`AgentMemory` is synchronized**; `AgentManager` maps are `ConcurrentHashMap`.
10. `AgentAction` is a **sealed interface** — adding a record requires updating the
    exhaustive `switch` in `AgentActionDispatcher#dispatch` (and the LLM tool mapping if
    you want the LLM to use it).
11. **Combat safety:** `AgentCombat.findPotion` only considers ids in `[2000000, 2060000)`
    and try/catches `getItemEffect` — projectile/food items have no `spec` and make the
    loader throw. Attack damage is conservative (server max can only be raised by buffs) and
    targets are only attacked well inside the distance-alert threshold. `behaviorStep`
    charges **one** action-budget token per tick and isolates each sub-behaviour in a
    try/catch; do not remove either.
12. **Attack routing is job/weapon/skill aware.** Basic (skill-0) attacks always use
    `CLOSE_RANGE_ATTACK` unless the agent wields a ranged weapon, because `MagicDamageHandler`
    NPEs on `SkillFactory.getSkill(0)`. Magic skills use `MAGIC_ATTACK` (chosen by
    `effect.getMatk() > 0`, so Cygnus/Evan work); physical skills use `RANGED_ATTACK` only
    when a ranged weapon and matching, level-valid ammo (>= the skill's `bulletCount`) are
    present. Out-of-ammo ranged agents skip rather than swinging melee.
13. **Attack skills are filtered** (`AgentCombat.isAttackSkill`): learned, not a buff, not
    `fixDamage`, magic requires `matk > 0`, physical requires `StatEffect#hasDamage()` (the WZ
    `damage` node actually exists — plain `getDamage()` defaults to 100 and would let Teleport/
    Shadow Web through), plus `EXCLUDED_ATTACK_SKILLS` (Meso Explosion, Shadow Meso, ranged-
    special skills). `canUseAttackSkill` also requires the skill's raw `mobCount > 0`.
    Otherwise a non-damaging skill trips `DAMAGE_HACK` (server max 0).
14. **Buff casting validates** via `canCastBuff` (learned, not cooling, `isSkill()`, statups,
    duration, MP/HP, not already active) before dispatching — `cast_buff` must never reach
    `skill.getEffect(0)`.
15. **Attack packets are byte-exact** against `AbstractDealDamageHandler#parseDamage`; the
    charge-skill list, the hurricane extra block, and the Poison Bomb tail are mirrored.
    Multi-target builders repeat the per-target block and pack the count into the high nibble
    of `numAttackedAndDamage`. Skill damage lines are `clamp(max(bulletCount, attackCount), 1..8)`
    — the same bound as the server's own "too many lines" guard — and repeated as that many
    identical damage ints per target. There are layout tests — keep parity when adding packets.
16. **Agents must not stay in the "changing maps" state.** `Character.mapTransitioning`
    defaults to `true` and is only cleared by the client's `PLAYER_MAP_TRANSFER` packet, which
    an agent never sends. `AgentManager.spawn` and `AgentMovement.warpTo` call
    `setMapTransitionComplete()`; any new code path that calls `Character#changeMap` for an
    agent must do the same or transition guards (`isChangingMaps()`) break.
17. **Cross-map follow is opt-in and guarded.** `LLM_AGENT_FOLLOW_WARP` (default off) warps a
    following agent to its target's map via `AgentMovement.warpTo`, which refuses dead/mid-
    transition agents, trades, open cash shops, event instances, and cross-channel targets.
    It uses the spawn-point `changeMap(int)` overload; there is a 4 s cooldown in `AgentSession`.
    This is trusted party-warp semantics (like `warpParty`) and deliberately skips portal/
    level/quest gating.
18. **Ammo resupply goes through the real shop.** `AgentCombat.rechargeAmmo` finds a shop NPC
    in `LLM_AGENT_RECHARGE_RANGE_SQ`, calls `Shop#recharge` for `ItemConstants.isRechargeable`
    items (throwing stars, bullets) and `Shop#buy` for arrows (which are not rechargeable in
    v83). Both pay mesos exactly like a player. The passive per-tick resupply is gated by
    `LLM_AGENT_AUTO_RECHARGE`; the explicit LLM `recharge_ammo` tool is always allowed.
19. **Movement is clamped per axis** (`AgentMovement.moveTo`), so a hallucinated destination can
    never teleport, and it is **ground-aware**: `moveToSmart` never moves an agent through open air.
    An airborne agent falls straight down to the foothold under its current x; a grounded agent
    walks only where a foothold exists under the next step (it samples the ground ahead and stops at
    the platform edge/gap) and only ever descends to a lower foothold — **except through an
    explicit pathfinder move**: when the direct step stalls (edge/gap/strictly-higher target),
    `AgentPathing.routeStep` takes the tick and may emit a bounded jump arc (stances 6/7) landing
    on a real foothold or a stance-16 climb along a real WZ `ladderRope`; every such move is still
    clamped by `MAX_STEP`, and a mid-climb agent is never "fallen" off its rope. Routing is
    bounded (256 footholds) and declines gracefully on oversized/unpathable maps. Whether an
    agent is mid-climb is tracked **explicitly** (`AgentSession#isClimbingRope`, set by the last
    emitted step) — position-based classification is ambiguous when a rope column overlaps
    platform surfaces, which froze agents at rope tops and dropped them off ropes mid-climb. `footholdAt`'s rounding recovery accepts a surface
    *above* the feet only within `GROUNDED_EPSILON`, so an overhead platform is never mistaken for
    ground. `AgentAction.MoveTo` (the LLM `move` tool) and `stepAway` (flee) are routed through
    `moveToSmart` for the same reason. Regression tests live in `AgentMovementFootholdTest`, and
    `AgentMovementWzRuntimeTest` falsifies the invariant against real `Map.wz` footholds (it skips
    when the WZ tree is unavailable).
    Cross-map
    follow first tries to walk to an **open, non-scripted** portal leading to the target map
    (`findPortalTo` skips closed/script portals) and enters it with the same event/trade/cash
    guards as `canWarpTo`; after `PORTAL_ATTEMPT_LIMIT` failed ticks it falls back to the
    `LLM_AGENT_FOLLOW_WARP` party-warp. Any new self-transfer must call
    `setMapTransitionComplete()`.
20. **Channel-follow re-logs the agent** via `AgentManager.transferChannel` (synchronized:
    capture state → synchronous `despawn` → `spawn` on the target channel). A failed re-spawn
    rolls back to the original channel; transfers are throttled per character and refused for
    null/mid-transition players. `AgentSession.behaviorStep` aborts after `followStep` if the
    session was despawned mid-step.
21. **Local skill cooldowns** (`AgentSession.isSkillReady`/`markSkillUsed`) mirror the WZ
    `cooldown`; cooldowns are only marked when `dispatchInbound` actually ran a handler.
22. **Proactive behavior is fully opt-in and cheap.** Every entry point in `AgentProactivity`
    is gated by `LLM_AGENT_PROACTIVE` plus its sub-switch, runs on the per-session action
    executor, and is self-throttled. `greet` selects read-only and marks a player only after
    the greeting was actually sent, skips agents/GMs/hidden players, and prunes its map.
    `matchmake` skips agents that are already following. Proactive actions never invoke the LLM.
23. **The LLM request budget is global and monotonic.** `OpenAiChatClient.tryConsumeCallBudget`
    enforces `LLM_MAX_CALLS_PER_MIN` (0 = unlimited) over a `System.nanoTime()` window; exceeding
    it throws, and the brain fails safe with an empty action list.
24. **Moderation survives transfers.** `AgentSession.setMuted` is an atomic volatile write;
    `AgentManager.transferChannel` captures and restores the muted flag. `dispatch` drops
    `Say`/`PartySay`/`Whisper` while muted **before** consuming rate budget or audit logging.
25. **Client animation depends on the movement/attack `stance` byte and the fragment
    duration/velocity.** Captured from a real client: walk `2`/`3`, stand `4`/`5`, rope/ladder
    climb `16`. `AgentMovement` uses these, derives the fragment duration from the step at
    `WALK_SPEED_PX_PER_SEC` (a fixed 120 ms made every step a dash), writes
    `xwobble`/`ywobble` as pixels-per-second (0 = animate without translating), and broadcasts
    an idle stance when the agent stops. Do not regress these without a client check.
    **Attacks/casts use a different `direction` byte:** it is an *action* value with the facing
    in the low bit (`AgentCombat.ATTACK_DIRECTION_ACTION = 22`, so `22` facing right / `23`
    facing left), while `stance` stays `0`, `speed` `6`, `display` `0`. Writing the raw facing
    (`0`/`1`) into `direction` makes the client silently skip the cast animation even though the
    damage still lands — this was the "agents don't animate" bug. `AgentCombat` (attacks) and
    `AgentSkill` (buffs) both mirror it. Verified by capturing a real client's printed packet
    (`who=qwerty skill=0 dir=22`, `skill=3001005 dir=22`) and by a live Fire Arrow rendering; if
    another class still does not animate, capture that class's real packet the same way (§5).
26. **Companions are server-owned and disposable.** `DbAgentProvisioner` creates a dedicated
    `agnt%` account + character; `AgentManager.despawn`/`stop` delete them, and
    `AgentManager.start()` reaps leftovers for ungraceful shutdowns. `CompanionAppearance`'s WZ
    candidate scan must stay **cached and off the request path** (an inline O(n^2) `getAllItems()`
    scan once hung `@agents find` while holding the per-player lock).
27. **Secrets never live in the repo.** `LLM_API_URL`/`LLM_API_KEY`/`LLM_MODEL` and
    `DB_USER`/`DB_PASS` are read from the environment (non-blank overrides `config.yaml`);
    `.env` is gitignored. Do not put keys back into `config.yaml`.
28. **In-game command headings are `@` (players) and `!` (GM).** A `/…` line is not dispatched
    and is silently dropped by `GeneralChatHandler`; docs/usage strings must not use `/`.
29. **`LLM_API_URL` must name the chat-completions endpoint.** `OpenAiChatClient` POSTs to the
    configured URL verbatim; `OpenAiChatClient.resolveEndpoint` completes a bare base URL (or
    `/v1`) to `/chat/completions`, but a wrong full path still 404s. Symptom: the agent spawns,
    then does nothing, and the log shows `HTTP 404`.
30. **Companion deletion needs the character's world.** `DbAgentProvisioner.destroy` deletes the
    character through a worldless mock `Client` (`world = -123`); `ProvisionedAgent` now carries
    `world` so the mock client resolves the real `World` before `Party#leaveParty` runs. Without
    it, `@agents dismiss` NPEs (`world` is null) and leaks the character/account.
31. **Tier-2 movement model (§4.20).** Movement physics are the server's own WZ values
    (`wz/Map.wz/Physics.img.xml`: `gravityAcc` 2000 px/s², `jumpSpeed` 555 px/s, `walkSpeed`
    125 px/s, `fallSpeed` 670 px/s — see `AgentPhysics`; apex `v0²/2g ≈ 77px`, flat-jump reach
    `walkSpeed·2v0/g ≈ 69px`). Everything downstream is derived from these — do not reintroduce
    magic jump/gap constants. Jump edges in the nav graph are **simulated arcs** that must land on
    the target surface and clip no intervening platform (rising *through* a platform is allowed —
    platforms are pass-through from below). Routing uses a per-map cached `AgentNavGraph`
    (built lazily, async-warmed, declined above 4000 stand points → legacy bounded BFS), and each
    session caches its route in `AgentMovementState` (invalidated by a moved target, map change,
    or 4 stalled ticks) so multi-tick manoeuvres are not re-picked every tick. Gravity falls use
    the air/jump stance — **never** the climb stance 16 — and `AgentMovement.moveAlong`/
    `walkOnGround` always emit walk stances even on steep slopes. Mocked `AgentSession`s (tests)
    return `null` from `movementState()` and take the stateless legacy path by design.

### 4.20 Tier-2 pathfinding & movement model (2026-10-20)

The pre-Tier-2 pathfinder re-ran a foothold-level BFS every tick, used magic jump constants
(`JUMP_HEIGHT_PX=100`, `JUMP_GAP_PX=120`) that did not match real physics, and re-picked its
manoeuvre every tick — the KPQ round-5/6 symptoms: stalls at platform edges, agents falling on
gap jumps, and agents stranded 1–30px below sloped surfaces in a grounded-but-unroutable limbo.
The Tier-2 model:

1. **Physics (`AgentPhysics`).** Constants come from the repo's own `wz/Map.wz/Physics.img.xml`
   (gravity 2000 px/s², jump 555 px/s, walk 125 px/s, fall 670 px/s). Derived: apex ≈ 77px,
   flat-jump airtime ≈ 0.555s, flat-jump reach ≈ 69px, and `jumpRangeForDrop` for lower landings
   (a 150px drop reaches ~94px). `simulateJump` integrates the parabola at 60fps from a snapped
   stand point and returns the landing point only if the arc descends onto the target foothold's
   surface inside its x-range without descending through any intervening foothold.
2. **Grounding.** After every horizontal step, `y` equals the support foothold's
   `calculateFooting(x)` — the direct step walks only over probed ground and descends straight
   down (never diagonally, which used to leave the agent off-surface mid-air at a clamped y).
   `footholdAt` still classifies an agent as grounded when it sits up to `SNAP_UP_PX = 30px`
   *below* a surface (slope slip), and the next step snaps it straight up onto the true surface —
   the old 8px window is what stranded agents below slopes. `PLATFORM_TOLERANCE` (90) is aligned
   with the jump apex so routed jumps are never misread as "different platform".
3. **Cached nav graph (`AgentNavGraph`).** Nodes are stand points every ~15px along each non-wall
   foothold (y snapped), edges are walk (incl. welds between contiguous equal-height segments),
   fall (physics fall time), arc-validated jump, and WZ `ladderRope` climb edges. Built lazily
   per map id on a daemon thread (mirroring the ladder cache's `warmAsync`), cached statically,
   and declined (sentinel cached) above `MAX_NODES = 4000` stand points. `routeStep` falls back
   to the legacy bounded BFS while a graph warms, for rope starts (graphs carry no rope nodes),
   and for declined maps. A* is time-costed (walk/fall/jump/climb speeds) with an admissible
   straight-line heuristic. Real-map build cost is ~75ms for a 979-node map, paid once.
4. **Per-session manoeuvre state (`AgentMovementState`).** A stalled agent stores its route and
   executes one clamped step per tick, advancing through walk→jump→walk chains without re-picking
   (the old re-pick race). State is invalidated by a target moved >30px (follow jitter stays
   within tolerance), a map id change (follow warp/portal), or 4 ticks without positional
   progress. Public seams are unchanged: `moveToSmart`, `routeStep(session, target, maxStep)`,
   `hangingOnLadder`, `ladders`, `isClimbingRope` all behave as before; the harness `MoveTo`
   action and `setup teleport` are unaffected (verified by the full suite).
5. **Real movement fragments.** Every step is an absolute fragment carrying the destination
   foothold id (best-effort, 0 when unsupported — the handler ignores it for absolute moves, so a
   wrong id cannot desync), duration derived from the step at walk speed, and pixel/s velocity.
   Jump manoeuvres append a real relative jump fragment (command 1, no positional delta) *after*
   the absolute fragment so clients animate the arc; single-fragment parsers still read the
   absolute step first.

Falsification: `AgentPathingTier2Test` (limbo recovery on slopes, clip-rejection, physics
constants, graph cache thread-safety, navigation completeness on KPQ 103000801–803 + Ellin
930000500 from the spawn portal, and the walk-to-takeoff gap jump the old pathfinder failed) plus
the pre-existing `AgentMovementFootholdTest` / `AgentMovementWzRuntimeTest` / `AgentMovementPathingTest`.

**Known issue (open):** on KPQ 103000802, driving `MoveTo (1100,-75)` from the -135 cloud chain
makes the agent cycle between the -135/-15/-75 levels instead of settling: the trace shows a
120px vertical jump-stance move (e.g. `(1100,-135) → (1100,-15)`) that physics forbids (apex 77)
and whose source hop was not identified within the time box; `nextSurfaceBelow`'s
findBelow-coverage validation and the chain-walking lower branch (both added for it) are in place,
and other targets on the same map — including a full rope/jump climb with a mid-route fall and
recovery to `(719,-236)` — complete correctly. Follow-up: log the executed hop type/via/dest at
emission time inside `executeHop` to catch the offending edge. Note also that the server's own
integer-division `calculateFooting` resolves shallow slopes (`|dy| < |dx|`) as flat at `p1.y`, so
a "-243" sloped platform's real surface can be y=-186 depending on vertex order — targets must be
chosen on the server-resolved surface (the graph's nodes already are).

---

## 5. How to extend (recipes)

### Add a new action
1. Add a `record` to `AgentAction` (sealed interface).
2. Handle it in `AgentActionDispatcher#dispatch` (add a `case`).
3. Build the synthetic packet exactly matching the target handler's read order, e.g.:
   ```java
   send(session, RecvOpcode.SOME_OPCODE, p -> { p.writeByte(...); p.writeInt(...); });
   ```
   Verify against the handler's `handlePacket` reads.
4. (Optional) LLM: add the tool to `OpenAiChatClient#buildToolsSchema` and a `case` in
   `LlmAgentBrain#toAction`.
5. Add a unit test if the encoding is non-trivial (`Packets`/`InPacket` readers).

### Add a new perceived event
1. Add a `record` to `AgentEvent`.
2. Add a hook at the choke point, e.g. `AgentManager.getInstance().onX(...)`, and route it
   to the right sessions in `AgentManager`.
3. Render it in `LlmAgentBrain#describeEvent` (switch is exhaustive over `AgentEvent`).

### Add a perception field
1. Add a component to `AgentObservation` (append at the end to minimize churn) and a nested
   record if needed.
2. Populate it in `AgentPerception` (use `rangeSq()`, cap with `MAX_OBSERVED`).
3. Render it in `LlmAgentBrain#buildUserPrompt`.
4. Update the test helpers that construct `AgentObservation` (`ScriptedAgentBrainTest` and
   `ScriptedAgentBrainLootTest`).

### Add a GM subcommand
Edit `AgentCommand.executeInternal` in `client/command/commands/gm5/AgentCommand.java`.
It already runs off the Netty loop.

### Verify agent animation with the real client (vision)

There is no automated check for "does the client render the cast" — verify it by looking.
1. Launch the client `C:\Nexon\MapleStory\HeavenMS-localhost-WINDOW.exe` (windowed) and log in
   (click the world button if the server was just rebuilt).
2. Summon a companion in-game by typing `@agents find` in chat.
3. Screenshot the client window from PowerShell — bring it topmost, then `CopyFromScreen` the
   window rect to a PNG — and read the PNG. To catch a short animation, capture a burst of frames
   (~80 ms apart) and tile them into one contact sheet.
4. To correlate server packet ↔ client pixels, temporarily log the broadcast attack fields in
   `PacketCreator.addAttackBody` (see §11.2), rebuild, and compare the agent's line
   (`who=Agent-…`) with a real client's (`who=<player>`). They must agree on
   `direction`/`stance`/`speed`/`display`; the client needs `direction` to be the action byte
   (`22 | facing`), not the raw facing.

PowerShell input notes: bring the window topmost (`SetWindowPos(... HWND_TOPMOST ...)`), click
inside the client area to focus, then drive it with `keybd_event` / `SetCursorPos`+`mouse_event`.
Injected input works for chat/UI; the client prints an "unlawful programs" notice when synthetic
keys are used, which is expected on a local server.

---

## 6. Configuration (`config.yaml`, `server:` section)

| Key | Default | Meaning |
| --- | --- | --- |
| `USE_LLM_AGENTS` | `false` | Master switch (gates auto-start **and** `!agent spawn`). |
| `LLM_AGENTS_MAX` | `4` | Max simultaneous agents. |
| `LLM_AGENT_TICK_MS` | `2000` | Per-agent tick interval. |
| `LLM_AGENT_PERCEPTION` | `BOUNDED` | `BOUNDED` (range-limited, fair-ish) or `OMNISCIENT` (dev). |
| `LLM_AGENT_VIEW_RANGE` | `722500.0` | Squared view radius (850²) for BOUNDED. |
| `LLM_API_URL` | `""` | OpenAI-compatible chat-completions endpoint. A bare base URL or `/v1` is completed to `/chat/completions`. Blank ⇒ scripted brain. Env `LLM_API_URL` overrides. |
| `LLM_API_KEY` | `""` | Bearer token. **Prefer the `LLM_API_KEY` env var** (`.env`, gitignored); never commit a key. |
| `LLM_MODEL` | `""` | Model name. Env `LLM_MODEL` overrides. |
| `LLM_MAX_CONCURRENT_CALLS` | `4` | Brain pool size / in-flight LLM requests. |
| `LLM_AGENT_NAME_PREFIX` | `[AI]` | Label convention for agent names. |
| `LLM_AGENT_CONTENT_FILTER` | `true` | Basic output filtering. |
| `LLM_AGENT_PERSONAS_FILE` | `""` | JSON/YAML persona file (see §7). |
| `LLM_AGENT_MEMORY_PERSIST` | `true` | Persist memory to `agent_memory`. |
| `LLM_AGENT_MAX_ACTIONS_PER_SEC` | `4` | Per-agent action rate limit. |
| `LLM_AGENT_REQUIRE_PREFIX` | `false` | Refuse spawn if name lacks the prefix. |
| `LLM_AGENT_FOLLOW` | `true` | Enable follow behavior. |
| `LLM_AGENT_AUTO_SUSTAIN` | `false` | Auto-use HP/MP potions when low. |
| `LLM_AGENT_AUTO_COMBAT` | `false` | Auto-attack the nearest monster. |
| `LLM_AGENT_HP_POTION_PERCENT` | `50` | HP % below which an HP potion is used. |
| `LLM_AGENT_MP_POTION_PERCENT` | `30` | MP % below which an MP potion is used. |
| `LLM_AGENT_ATTACK_RANGE_SQ` | `150000.0` | Squared auto-attack range (~387px). |
| `LLM_AGENT_AUTO_BUFF` | `false` | Auto-cast configured buff skills. |
| `LLM_AGENT_AUTO_BUFF_SKILLS` | `""` | Comma-separated buff skill ids. |
| `LLM_AGENT_ATTACK_SKILL` | `0` | Force one attack skill id for all agents. `0` = auto-select the best learned attack skill per agent (falls back to a basic attack). |
| `LLM_AGENT_AUTO_HEAL` | `false` | Auto-cast a heal skill when the agent/party is low. |
| `LLM_AGENT_AUTO_HEAL_SKILL` | `0` | Heal skill id (0 = auto-detect the learned heal skill). |
| `LLM_AGENT_HEAL_PERCENT` | `90` | HP % below which auto-heal fires. |
| `LLM_AGENT_AUTO_LOOT` | `false` | Auto-pick up owned/party/FFA drops every behaviour tick. |
| `LLM_AGENT_AOE_ATTACK` | `false` | Allow a multi-target skill attack to hit several monsters. |
| `LLM_AGENT_AOE_MAX_TARGETS` | `6` | Upper bound on monsters hit by one AOE attack. |
| `LLM_AGENT_FOLLOW_WARP` | `false` | Warp the agent to its follow target's map when they leave the current map. |
| `LLM_AGENT_AUTO_RECHARGE` | `false` | Each behaviour tick, recharge rechargeable ammo (stars/bullets) at a shop NPC for mesos. |
| `LLM_AGENT_RECHARGE_RANGE_SQ` | `150000.0` | Squared distance within which an NPC can act as a recharge shop (~387px). |
| `LLM_AGENT_AUTO_FLEE` | `false` | Retreat from monsters when HP drops below `LLM_AGENT_FLEE_PERCENT`. |
| `LLM_AGENT_FLEE_PERCENT` | `25` | HP % below which auto-flee fires. |
| `LLM_AGENT_FOLLOW_CHANNEL` | `false` | Follow the party leader across channels (re-logs the agent on the target channel). |
| `LLM_AGENT_PROACTIVE` | `false` | Master switch for proactive (non-reactive) behavior. |
| `LLM_AGENT_GREET` | `true` | With PROACTIVE on, greet nearby players once each. |
| `LLM_AGENT_MATCHMAKE` | `true` | With PROACTIVE on, auto-register for party search (auto-invites nearby solos). |
| `LLM_AGENT_PATROL` | `false` | With PROACTIVE on, wander the map when idle. |
| `LLM_AGENT_GREET_COOLDOWN_MS` | `600000` | Minimum ms between greetings of the same player. |
| `LLM_AGENT_MATCHMAKE_INTERVAL_MS` | `60000` | Minimum ms between party-search (re)registrations. |
| `LLM_AGENT_PATROL_INTERVAL_MS` | `8000` | Minimum ms between patrol steps. |
| `LLM_AGENT_GREETING` | `""` | Greeting template (`{name}` replaced); blank uses the brain/persona greeting. |
| `LLM_AGENT_MEMORY_SUMMARIZE` | `true` | Fold old dialogue into durable memory facts. |
| `LLM_MAX_CALLS_PER_MIN` | `60` | Global cap on LLM requests per minute (0 = unlimited). |
| `LLM_AGENT_NOTICE` | `false` | Broadcast a map notice that an agent is AI-controlled when it spawns. |
| `LLM_AGENT_PLAYER_SPAWN` | `false` | Allow rank-0 players to summon ephemeral companions with `@agents find`. |
| `LLM_AGENT_MAX_PER_PLAYER` | `1` | Max simultaneous companions summoned by one player. |
| `LLM_AGENT_REQUEST_COOLDOWN_MS` | `60000` | Minimum ms between `@agents find` requests by one player. |
| `LLM_AGENT_LEVEL_MATCH` | `EXACT` | `EXACT` (requester's level) or `BAND5`. |
| `LLM_AGENT_AUTO_PARTY` | `true` | Auto form/join a party with the summoned companion. |
| `LLM_AGENT_COMPLEMENT_JOB` | `true` | Prefer a role that complements the requester: a different class (healer first, weighted), never the requester's own job. |
| `LLM_AGENT_ACCOUNT_PREFIX` | `agnt` | Prefix for server-owned accounts created for ephemeral companions. |
| `LLM_AGENT_COMPANION_NAME_PREFIX` | `Agent-` | Name prefix for summonable companion characters (`Agent-<FunWord>`). |

**Personas file** (JSON or YAML), either shape:
```json
{ "Alice": { "description": "cheerful cleric", "systemPrompt": "You are Alice..." } }
```
```yaml
- name: Alice
  description: cheerful cleric
  systemPrompt: You are Alice...
```

---

## 7. Database

`database/sql/migration/v0.15.0.sql`:
```sql
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `characterid` int(11) NOT NULL,
  `facts` text,
  `dialogue` text,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
```
Facts and dialogue are stored newline-delimited. `AgentMemoryStore` never throws on DB errors.

`database/sql/migration/v0.16.0.sql`:
```sql
CREATE TABLE IF NOT EXISTS `agent_optout` (
  `characterid` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
```
`AgentOptoutStore` loads the whole set into `AgentManager` at start and toggles rows on
`@agentoptout`; failures are logged, never thrown.

---

## 8. What's done vs. not

**Done**
- M0: headless puppet player, real bootstrap, `!agent spawn`.
- M1: reactive chat (general/party/whisper), LLM brain with tool calling, personas, memory.
- M2: party invite accept/deny/join/leave, party chat, follow-the-leader (same-map walk +
  cross-map warp), party-search listing.
- M4: opt-in proactive behavior (greet once per nearby player, auto party-search
  matchmaking that invites nearby solos, idle patrol), persona `greeting`/`traits`,
  memory compaction (single rolling summary fact), a global `LLM_MAX_CALLS_PER_MIN` request
  budget, `!agent status|mute|unmute`, and an optional map AI notice.
- M3 (complete): monster/item/NPC perception, clamped foothold-aware movement, follow
  (same-map walking, portal-based cross-map travel, opt-in cross-channel re-login), item
  pickup, potion self-sustain, respawn, melee/magic/ranged basic + skill attacks with
  multi-line damage (`max(bulletCount, attackCount)`), multi-target skill attacks (incl. the
  LLM `attack_many` tool), buff-skill casting, heal-skill casting, auto-looting, target
  facing, local skill cooldowns + HP/MP-cost gating, auto-flee, ammo perception
  (`rangedWeapon`/`ammoCount`/`outOfAmmo`) and shop resupply (recharge stars/bullets, buy
  arrows), plus LLM `follow`/`stop_following` movement tools.
- Player-facing opt-out: `@agentoptout` (rank 0) and GM `!agent optout <player>` suppress
  agent greetings, party-search invites, whispers, party invites, party chat and follow;
  persisted in `agent_optout` and loaded at start.
- Ground-aware walking (see §4.19): `moveToSmart` never moves an agent through open air — it
  samples the ground ahead, stops at platform edges/gaps, falls straight down when airborne,
  never ascends, and refuses overhead platforms. Verified by `AgentMovementFootholdTest` and the
  real-`Map.wz` `AgentMovementWzRuntimeTest` (6000 steps, 0 counterexamples).
- Combat animation: agent attack/cast packets now carry the real client's `direction` **action**
  byte (`AgentCombat.ATTACK_DIRECTION_ACTION = 22`, `22 | facing`), so skill casts render on
  other clients (confirmed live for a mage's Fire Arrow). Agents also auto-select the strongest
  learned class attack skill each tick (`AgentCombat.selectAttackSkill`), so mixed-class
  companions cast real skills instead of always basic-attacking.
- Ops: rate limiting, audit logging, content filter, config, GM command, wake hooks for
  map-change and party-update.

**Not done (next work)** — see **§11** for file pointers and suggested approaches.
- **Pathing depth — DONE (2026-10-19, §11.1); Tier 2 — DONE (2026-10-20, §4.20):** movement is
  clamped, ground-aware waypoint stepping, and routed via a per-map cached navigation graph with
  real v83 jump physics (`AgentPhysics`/`AgentNavGraph`, A*), per-session manoeuvre state
  (`AgentMovementState`), real movement fragments (jump fragment + destination fh id), and
  gravity-fall/slope-walk stance fixes. See §4.20, `AgentPathingTier2Test`. Remaining cosmetics:
  ropes/ladders are treated identically (no one-way semantics). Patrol randomizes X only.
- **Combat fidelity:** hold-skill after-delay pacing and the ranged cast stance for
  Hurricane/Rapid Fire-family skills **are implemented**. The per-weapon action byte and
  attack-speed questions were **closed (2026-10-19)**: every class captures/sends the same
  `22 | facing` action value (generic v83 attack action — the receiving client animates from
  the attacker's avatar), and `speed=6` matches the real client's baseline. Per-weapon
  booster-adjusted speed is accepted as out of scope.
- **`!agent tell`:** intentionally dropped (an operator-only directive of marginal value; the
  prototype was discarded). `@agents pause|resume` and GM `!agent pause|resume` **are implemented**.
- **Player-facing companions (M5–M7):** implemented (`@agents list|find|pause|resume|dismiss`)
  with level-appropriate builds (`CompanionTemplate`: job tiers, AP, skills, gear);
  see **`docs/llm-agent-companions-plan.md`**. M9 (party-quest audit) is **closed** —
  companions are blocked from scripted events and CPQ (§11.1, `docs/llm-agent-pq-audit.md`);
  M8 (idle despawn/uptime caps) is intentionally not pursued — the per-player cap + request
  cooldown + no-value-transfer + no-auto-loot posture is accepted.
- **Persistence guidance:** the two agent migrations are manual (like other repo migrations);
  without them, memory/opt-out changes simply do not survive a restart.


---

## 9. Review history

Independent verifier passes were run for each milestone (each followed by a fixes commit); all
HIGH/MEDIUM findings were addressed:
- **Foundation/M1** (`54ef8c76f`): command injection via `@`/`!`; double-login kicking real
  players; partial-login ghost; async despawn race.
- **M2/M3** (`0cc8eb539`): unclamped/teleport movement; pre-mutation follow state; rate-limiter
  races and `Remember` starving chat; pickup range mismatch; uncapped player list.
- **Combat** (`a72a98f33`): `findPotion` NPE on spec-less items; percentage potions;
  event-aware respawn; per-tick single rate charge; error isolation; range enforcement.
- **Buffs** (`b89f75a3e`): unvalidated `cast_buff`; charge-list packet desync; cooldown/HP-cost;
  `isSkill()` active-buff match.
- **Skills** (`ad2ddacb4`): skill-0 magic NPE; non-damaging-skill autoban; Meso/Shadow/ranged
  exclusions; Cygnus/Evan opcode; LLM fallback.
- **Ranged** (`8f0a6b9e5`): ammo type/level/bulletCount mismatch; Shadow-Claw no-op;
  magic-skill misrouting; out-of-ammo melee fallback; Poison Bomb tail.
- **Combat depth 2** (`ac779b70a`): heal-skill casting via `SPECIAL_MOVE`; auto-looting of
  owned/party/FFA/expired drops; multi-target skill attacks gated by `LLM_AGENT_AOE_ATTACK`.
  Fixes from two further verifier passes: spawn/login TOCTOU (`registerAgentClientIfAbsent`),
  `lastActions` scheduling race, damage-less-skill misclassification (`StatEffect#hasDamage()`),
  `AUTO_BUFF`-only never ticking, loot owner rule now matches `Character#pickupItem`, and
  `cast_heal` now resolves skill id 0.
- **Cross-map follow** (`2fb00ad3c`): warps a following agent to its target's map when they leave
  (`LLM_AGENT_FOLLOW_WARP`, default off), guarded by `AgentMovement.canWarpTo`. Verifier fix:
  agents were permanently `isChangingMaps()` (they never send `PLAYER_MAP_TRANSFER`), which made
  the warp unreachable and would also have broken it after the first warp — now cleared at spawn
  and after each warp; also switched to the spawn-point overload to avoid a portal-0 NPE.
- **Player opt-out**: two verifier passes. Fixed: party chat (both directions) and follow were
  un-gated bypasses (party chat now checks the speaker and drops party-say when a member has
  opted out; follow resolves and refuses opted-out targets, including auto-follow); the
  `@agentoptout` command no longer does JDBC on the Netty loop (offloaded to `ThreadManager`);
  `AgentOptoutStore` catches `Exception` (not `Throwable`) and uses `ON DUPLICATE KEY UPDATE`;
  and the greet test was a false positive (it never stubbed `isGreetDue`).
- **M4** (proactive matchmaking, personas, memory compaction, cost controls, notice, mute):
  three verifier passes. Fixed: `greet` marked every candidate as greeted (now selects read-only
  and marks only the successfully-greeted player); `matchmake` registered agents that were
  already following a leader (now skips); memory `compact` appended a new summary fact each time
  and could evict real facts (now replaces a single rolling summary, code-point-safe truncation);
  the LLM budget used wall-clock (now `nanoTime`); mute was lost across channel transfer (now
  captured/restored) and used a racy toggle (now an atomic `setMuted`); agents could auto-invite
  other agents via party search (now excluded).
- **M3 complete** (facing, cooldowns, HP-cost, arrow purchase, flee, smart movement + portal
  follow, channel-follow, LLM follow tools): three verifier passes. Fixed: portal branch wedged
  follow and bypassed the event/trade/cash guards (now skips closed/scripted portals, shares
  `canSelfTransfer`, and falls back to the warp after N attempts); `behaviorStep` kept running
  on a despawned session after a channel transfer (now aborts); failed channel transfers
  permanently deleted the agent (now rolls back to the original channel, throttled per
  character); `despawn(int)` races (now synchronized); battle cooldowns were marked even when a
  packet was dropped by `validateState` (now only on a handled dispatch); AOE facing re-fetched
  a possibly-despawned target (now captured during validation); arrow purchase aborted on the
  first unaffordable stock line (now scans on). `MapleMap.portals` is now a
  `ConcurrentHashMap` for safe iteration.
- **Combat depth 3** (multi-line + LLM multi-target + ammo): two verifier passes. Fixed
  `damageLines` to mirror the server's `max(bulletCount, attackCount)` guard (was `attackCount`,
  which under-sent Triple Throw/Strafe/Burst Fire by 2–4×); replaced the free `getSlotMax` refill
  with the real `Shop#recharge` meso path (and only `isRechargeable` stars/bullets); bounded the
  LLM target list (defensive-copy + cap at 15, `intArray` cap); made `outOfAmmo` use the largest
  required ammo among learned attack skills; honoured Shadow Claw alongside Soul Arrow; and added
  `Inventory#listCopy()` so ammo perception cannot race the action executor.

Known low-risk remainder: `!agent search` and auto-matchmaking enable party-search
auto-invites (documented); movement is Tier-2 (§4.20: cached nav graph, WZ-physics jump arcs,
per-session manoeuvre state) — one documented oscillation case on 103000802 target (1100,-75)
remains open with a debugging plan in §4.20; and there is latent cross-thread map mutation on
very busy maps (soak-test target; map collections use read/write locks).

---

## 10. Git history (this branch, newest first)

| Commit | Summary |
| --- | --- |
| `c972f78fa` | feat: tier-2 pathfinding — cached nav graph, WZ jump physics, manoeuvre state, real fragments (§4.20) |
| `094c3ff89` | fix: skip null junior slots in family observation (live-found NPE) |
| `06a8d63ab` | feat: ReactorHit/CatchItem/DropItem actions; Ellin clear fix + LPQ stage-3 seeding |
| `3d1d8840c` | feat: guild/alliance/marriage/family harness actions — real packet flows, state-delta verification |
| `f156d5cae` | fix: thread-safe script/EIM maps (quiz-state race), observe null-safety, ranking null guard |
| `7790c5978` | feat: exact-position teleport, kill-with-drops loot fix; full KPQ E2E + live interaction probe |
| `73aaa8dd9` | feat: PlayerInteraction actions — minigames + hired merchant, with verification |
| `fd0c6a118` | docs: §11.1 closed — pathing depth, test gaps, per-weapon animation analysis |
| `6ff7dd980` | test: §11.1 test gaps (ammo shop, opt-out gates, transfer channel) |
| `f8064f241` | feat: foothold + rope pathfinding (`AgentPathing`) — §11.1 pathing depth |
| `4a90290b7` | docs: M9 PQ audit + all attack animations verified live |
| `a636de9b5` | fix: keep ephemeral companions out of party quests and CPQ (M9) |
| `cda7ee885` | agent: per-class auto attack-skill selection + client attack-`direction` byte (casts render) |
| `f2247025b` | agent: ground-aware walking (no more air-walking) + real-`Map.wz` runtime test |
| `0b0c85a5e` | agent: endpoint completion, role diversity, find/dismiss feedback, dismiss fix |
| `a7a6288c7` | agent: diversify companion roles; acknowledge find/dismiss; fix dismiss world NPE |
| `c6610fb63` | agent: complete bare LLM base URLs to the chat-completions endpoint |
| `6c944199b` | agent: use the climb stance for vertical companion steps |
| `911fe776c` | agent: load companion cash-outfit candidates off the request path (fixes a `find` hang) |
| `057f3f043` | agent: normal walking pace; temporary stance capture |
| `98531c6e2` | agent: `Agent-<FunWord>` names + randomized cosmetics |
| `24a2fc239` | config: load LLM/DB secrets from the environment; gitignore `.env` |
| `489475c62` | agent: reap orphaned companion accounts on startup |
| `c4f60da43` | agent: broadcast movement velocity + idle stance so clients animate agents |
| `60c7b98e7` | docs: sub-level-10 companion behavior |
| `774ae8455` | agent: correct in-game command headings (`@` players / `!` GM) |
| `6ce1c0114` | config: enable player-facing companions (`LLM_AGENT_PLAYER_SPAWN`) |
| `070de1451` | agent: DB-backed provisioner integration test; fix live-path bugs |
| `3a882270e` | docs: companion recipe breadth done |
| `3b96e13d9` | agent: level-aware thief fallback weapon; harden WZ template test |
| `3b8d163b7` | agent: correct companion skill ids and tier builds |
| `57ba9bc70` | agent: align companion weapon types with granted skills |
| `fcc453af7` | agent: level-appropriate companion templates (jobs, stats, skills, gear) |
| `8d26ebf8c` | docs: record accepted anti-AFK posture (M8 not pursued) |
| `265a5bc3a` | docs: `/agent tell` intentionally dropped |
| `fc7037d96` | agent: serialize pause with transfers; strengthen tests; docs |
| `30f64021d` | test(life): make `MobSkillFactoryTest` order-independent |
| `29b42b4fc` | agent: player `@agents pause|resume` + GM `!agent pause|resume` |
| `4855cf45d` | test: cover the spawn-during-stop guard |
| `06e94082d` | agent: foothold-aware platform stepping |
| `e4072e1d6` | agent: pace hold-skill attacks + fix ranged cast stance |
| `5a0fabf88` | agent: pass-3 companion fixes (account orphan, despawn tombstone, stop guard) |
| `472c6b6dc` | agent: pass-2 companion fixes (loot gate, pool liveness, despawn monitor) |
| `07bd27510` | agent: companion lifecycle / TOCTOU / auto-loot review fixes |
| `5eddcd054` | agent: player-facing ephemeral companions (`@agents`) |
| `d1a1f0f7b` | agent: companion config keys + `AgentProvisioner` seam |
| `b73658cc0` | docs: player-facing companion plan (M5+) |
| `b11c957de` | docs: add a fresh-agent resume section to the handoff |
| `161cdcd81` | config: enable the agent starter profile from the guide |
| `e3013298c` | docs: docker does not auto-run migrations |
| `69205e862` | docs: add a player/operator usage guide |
| `4f3b6fa62` | docs: reconcile handoff/plan with the shipped opt-out |
| `b8a9d8105` | opt-out: serialize startup load against toggles |
| `a757e52e5` | docs: document the player opt-out |
| `9a1067173` | player-facing opt-out from AI-agent interaction |
| `4cb347332` | M4: proactive matchmaking, personas, cost controls, moderation |
| `fb6f7d246` | finish M3: combat depth, pathing/portal follow, channel-follow, LLM follow |
| `45dd0e2d2` | multi-line + LLM multi-target attacks, ammo perception/recharge |
| `2fb00ad3c` | cross-map follow (warp-with-party) |
| `ac779b70a` | heal casting, auto-loot, multi-target attacks + review fixes |
| `8f0a6b9e5` | ranged-attack review fixes + ranged tests |
| `b57d7c8c0` | ranged attacks |
| `ad2ddacb4` | skill-attack review fixes + skill tests |
| `53503ecb0` | melee/magic skill attacks |
| `b89f75a3e` | buff review fixes + buff skills in perception |
| `41805f1ff` | buff skill casting |
| `a72a98f33` | combat review fixes + combat tests |
| `367530db1` | combat assist (potions, respawn, basic melee attack) |
| `2994ee6a8` | developer handoff doc; plan marked implemented |
| `0cc8eb539` | M2/M3 review fixes + tests |
| `41c0c11b0` | perception, personas, movement/follow, loot, party search, rate limits |
| `54ef8c76f` | address first review findings |
| `d3be1c1bd` | OpenAI-compatible LLM brain, persistent memory, tests |
| `653f8fb77` | foundation (M0/M1 scaffold) |
| `9ad2b2d6c` | base (upstream `master`) |

Base branch: `master`. Continue on `feature/llm-agent-players` (it is pushed and in sync).

---

## 11. Resuming the work (fresh-agent checklist)

The engine (M0–M4), the player opt-out, and player-facing ephemeral companions (M5–M7) are
implemented, reviewed, tested and pushed. If you are picking this up cold:

1. Read `docs/llm-agent-players-guide.md` (behavior + commands), then this file (§2 architecture,
   §3 file map, §4 invariants, §5 recipes). Companions have their own design doc:
   `docs/llm-agent-companions-plan.md`.
2. Confirm the baseline: `mvn -q clean test` (green). Fast loop:
   `mvn -q -Dtest='net.server.agent.*Test' test`. Compile only: `mvn -q -DskipTests compile`.
   The live-path integration test is opt-in:
   `mvn -q -Dagent.it.db.port=3307 -Dtest=DbAgentProvisionerIT test`.
3. Implement on this branch. Follow the repo's review pattern: implement → run an *independent*
   read-only verifier pass (spawn a subagent) → fix HIGH/MEDIUM findings → update docs →
   commit → push. Local secrets (LLM API key, DB password) come from `.env` (gitignored) — copy
   `.env.example`; see §11.2.

### 11.1 Open work

| Item | Where | Notes / suggested approach |
| --- | --- | --- |
| **Companion animation** | `AgentMovement`, `AgentCombat`, `AgentSkill` | **Closed (2026-10-19).** Walk `2/3`, stand `4/5`, climb `16`; idle stance on stop; walking pace derived from the step; attack/cast `direction` action byte `22 | facing` with `stance=0`, `speed=6`, `display=0`. All three attack types verified live (magic Fire Arrow, melee Fighter sword, ranged Assassin Lucky Seven). The action byte needs **no per-weapon table**: every captured class sends the same `22|facing` (it is the v83 generic attack-action value — the receiving client picks the per-weapon animation from the attacker's avatar), and `speed=6` matches the real client's own baseline packet. Per-weapon booster-adjusted speed would need WZ attack-delay parsing — accepted as out of scope. Note: `USE_AUTOHIDE_GM` hides GMs on login and hidden clients silently refuse to attack — `!unhide` before any in-client capture session. |
| **Foothold/rope pathing depth** | `AgentMovement`, `FootholdTree` | **Closed (2026-10-19 + Tier 2, 2026-10-20, §4.20).** Ground-aware stepping, a cached per-map nav graph (`AgentNavGraph`: stand points, walk/weld/fall/jump/climb edges, A*) with jump arcs validated against the server's own WZ physics (`AgentPhysics`, `Physics.img.xml`), per-session manoeuvre state, real movement fragments (jump fragment + fh id), limbo recovery below slopes, and gravity-fall/slope-walk stance fixes. Legacy bounded BFS remains as the fallback (rope starts, warming, >4000-node maps). |
| **M9 — party-quest compatibility audit** | `docs/llm-agent-pq-audit.md`, `EventManager`, `EventInstanceManager`, `NPCConversationManager`, `MonsterCarnival` | **Closed.** The audit found companions break most PQs (auto-registered/stranded/counted, dismissal aborts the run) and recommended blocking them from all scripted event instances. Implemented and verifier-passed: `AgentManager.isEphemeralAgent`, an under-writeLock early-return in `EventInstanceManager.registerPlayer`, an eligibility filter in `EventManager.getEligibleParty`, and full CPQ coverage (`isCPQParty`/`canStartCPQ`/`MonsterCarnival` entry loops). Persistent GM agents remain PQ-eligible by design. See `docs/llm-agent-pq-audit.md`. |
| **Fidelity/test gaps** | `AgentCombat.buyAmmoFromShop`, the opt-out routing gates (`onMapChat`/`onWhisper`/`onPartyInvite`), `AgentManager.transferChannel` | **Closed (2026-10-19):** `AgentCombatAmmoShopTest` (8 tests: recharge-vs-buy, affordability, scan-on, stock/weapon gating), `AgentManagerOptoutGateTest` (8 tests: chat/whisper/invite suppression), `AgentManagerTransferChannelTest` (10 tests: rollback, moderation capture, refusals, throttle). Provisioning has `DbAgentProvisionerIT`. |
| **`!agent tell`** | `AgentCommand` | Intentionally dropped (operator-only; marginal value). |
| **M8 — idle despawn / uptime caps** | — | Intentionally not pursued; the accepted posture is per-player cap + request cooldown + no value transfer + no auto-loot. |

### 11.2 Environment state (as of the latest session)

- **Run it:** `docker compose up --build -d` (services `maplestory` + `db`; DB host port
  **3307** → container 3306, schema `cosmic`). `config.yaml` and `wz/` are volume-mounted, so a
  config-only change just needs `docker compose up -d`; a code change needs `--build`. Logs:
  `docker compose logs -f maplestory`.
- **Secrets are environment-driven.** `AgentConfig` reads `LLM_API_URL`/`LLM_API_KEY`/`LLM_MODEL`
  and `DatabaseConnection` reads `DB_USER`/`DB_PASS` from the environment (a non-blank env var
  wins); `config.yaml` keeps blank placeholders. Put real values in `.env` (gitignored; copy
  `.env.example`) and `docker-compose.yml` forwards them. **Never commit a key.**
- **Database/migrations:** the agent tables are applied (`agent_memory` v0.15.0, `agent_optout`
  v0.16.0). `docker compose`'s init dir does **not** run `database/sql/migration/`, so apply
  them manually:
  ```bash
  docker exec -i cosmic-ms-rebirth-db-1 mysql -uroot -p<pw> cosmic < database/sql/migration/v0.15.0.sql
  docker exec -i cosmic-ms-rebirth-db-1 mysql -uroot -p<pw> cosmic < database/sql/migration/v0.16.0.sql
  ```
  A local (non-compose) run needs the JDBC port changed to **3307** (`DB_URL_FORMAT`) unless the
  host is `db` (compose).
- **Config:** starter profile on (`USE_LLM_AGENTS: true`, auto-*, `LLM_AGENT_PLAYER_SPAWN: true`).
  With a blank `LLM_API_URL` agents use the deterministic **scripted** brain; set an
  OpenAI-compatible endpoint (via `.env`) for real LLM behavior.
- **In-game commands:** players use `@agents ...`/`@agentoptout`; GMs use `!agent ...`.
  **`/…` does not dispatch** on this server.
- **Temporary diagnostics:** none in the committed tree. To debug client-side rendering, add a
  temporary `System.out.println` of the broadcast attack fields in
  `PacketCreator.addAttackBody` (and/or the agent's chosen action in `AgentCombat.attackNearest`),
  rebuild, and read the fields for both an agent and a **real** client attack (the `who=` name
  distinguishes them). Remove before committing.
- **Observing the game (vision):** the v83 client is at
  `C:\Nexon\MapleStory\HeavenMS-localhost-WINDOW.exe` (windowed). A `pi` agent can screenshot and
  drive it via PowerShell — see the §5 recipe "Verify agent animation with the real client".
  `config.yaml`/`wz/` are bind-mounted, but a **Java change needs `docker compose up --build -d`**;
  restarting the server drops the client to the world-select screen and reaps ephemeral
  companions (`@agents find` again after).
- **Branch state:** `feature/llm-agent-players`; worktree clean; **not merged to `master`**
  yet. All §11.1 items are closed; Tier-2 pathfinding (§4.20) landed 2026-10-20. Remaining
  accepted cosmetics: ropes/ladders treated identically (no one-way semantics), per-weapon
  booster-adjusted speed.

### 11.3 Definition of done for a change

`mvn -q clean test` green (and the opt-in `DbAgentProvisionerIT` green if you touched
provisioning); an independent verifier pass with HIGH/MEDIUM findings addressed; `handoff` +
`plan` updated (and `guide` if user/operator-facing); no temporary diagnostic logging left in;
no secrets committed (keys go in `.env`); worktree clean; committed and pushed with a
`feat(agent):` / `fix(agent):` / `docs(agent):` message.

### 11.4 Player-facing companions (M5–M7, shipped)

The GM-only spawn model does not serve solo play. Players summon **ephemeral, server-provisioned**
companions with `@agents list|find|pause|resume|dismiss`; the full design is in
**`docs/llm-agent-companions-plan.md`**. Key points:

- `DbAgentProvisioner` allocates a dedicated server-owned account (`LLM_AGENT_ACCOUNT_PREFIX`,
  default `agnt`) and builds the character through the normal recipe path
  (`CharacterFactoryRecipe` + `insertNewChar`). The name is `Agent-<FunWord>`
  (`LLM_AGENT_COMPANION_NAME_PREFIX`), level = requester's level, job = a role that complements
  the requester unless one is passed — always a *different* class, healer-first weighted
  (`CompanionTemplate` picks the advanced job/stats/skills/gear for that level tier).
- `CompanionAppearance` gives a random hair/face/skin and a random **cash** outfit. Cash items
  are worn at `regular slot - 100` (hat -101 … weapon -111). The candidate scan is cached and
  **warmed on a background thread at startup** — never call the WZ scan inline (an earlier
  `getAllItems()`/`getItemData()` version was O(n²) and hung `find`).
- `AgentCompanionService` enforces the feature switch, eligibility (dead / mid-transition /
  trade / cash shop / event / opted-out), map, per-player cap, request cooldown and global
  capacity, all under a per-player stripe lock.
- Ephemeral sessions are flagged (`AgentSession#isEphemeral`) so memory is never persisted,
  auto-loot is forced off, and `AgentManager.despawn` deletes the character (covering
  `@agents dismiss`, GM `!agent despawn` and `stop()`); `transferChannel` carries the
  ephemeral/owner/provisioned/paused state across a re-spawn. `AgentManager.start()` also runs a
  **startup reaper** that deletes leftover `agnt%` accounts (ungraceful shutdowns).
- New config: `LLM_AGENT_PLAYER_SPAWN`, `…_MAX_PER_PLAYER`, `…_REQUEST_COOLDOWN_MS`,
  `…_LEVEL_MATCH`, `…_AUTO_PARTY`, `…_COMPLEMENT_JOB`, `…_ACCOUNT_PREFIX`,
  `…_COMPANION_NAME_PREFIX`.
- **Live-path gotcha found by `DbAgentProvisionerIT`:** `Client.createMock()` has a null Netty
  channel, so `insertNewChar`'s `sendPacket` used to NPE — `Client#writePacket` now drops the
  packet when there is no channel.
- Verified by multiple independent verifier passes (fixes in `07bd27510`, `472c6b6dc`,
  `5a0fabf88`, `fc7037d96`) plus the DB integration test. Open: the attack-animation stance
  (see §11.1); GM-spawned agents remain persistent and unchanged. Below level 10 a companion is
  a `BEGINNER` and auto-party is skipped (server starter-party gate) but it still follows.
