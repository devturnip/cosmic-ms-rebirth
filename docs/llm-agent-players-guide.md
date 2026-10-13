# LLM Agent Players — Player & Operator Guide

Cosmic (MapleStory v83) can run **agent players**: server-owned characters driven by an
OpenAI-compatible LLM (or by a deterministic scripted brain). They log in through the same
bootstrap a human uses, appear/move/chat/party/level like a normal player, and — when the
operator enables it — proactively greet people, list themselves in party search, and patrol.

This guide is for **players** who meet agents and for **operators** who turn the feature on.
For architecture, internals and the developer handoff, see
[`llm-agent-players-handoff.md`](llm-agent-players-handoff.md) and
[`llm-agent-players-plan.md`](llm-agent-players-plan.md).

> The feature is **opt-in and default off**. On a vanilla server nothing here exists.

---

## 1. For players

Agent characters are visible by their name prefix (default `[AI]`, e.g. `[AI] Alice`). Apart
from that they behave like ordinary players.

### Chatting

- **Map chat** — talk to them like anyone else; a reactive agent replies when addressed.
- **Whisper** — use the client's whisper UI (or its whisper shortcut) to message the agent; it replies privately.
- **Party chat** — works normally once you are partied with an agent.

Keep in mind agents are LLM-driven and rate-limited: they answer at most a few actions per
second and think on a ~2-second tick, so replies can take a moment.

### Partying up

- **Invite an agent** — target them and use the normal party invite. Reactive agents accept
  by default (they can also decline).
- **Find an agent** — if the operator enabled proactive matchmaking, agents register with
  the in-game **party search**; queuing there can match you with one automatically.
- **Party play** — the agent follows the party leader, chats in party chat, helps fight,
  heals/buffs (if configured), and picks up eligible drops.

### Fighting together

Depending on operator configuration, an agent in your party can:

- follow you (same map; across maps/channels if enabled),
- attack nearby monsters (basic attacks and/or learned damaging skills),
- cast buffs and heal you when you are low,
- drink HP/MP potions when low, flee when in danger, and
- loot drops it owns or that your party owns.

Agents never use the real client, so they will not have client-only cosmetics (no chair
animations, etc.), but their actions go through the server's normal validation.

### Seeing a bot notice

If the operator turns on `LLM_AGENT_NOTICE`, a short map notice announces when an agent
spawns (e.g. "`[AI] Alice` is an AI-controlled agent.").

### Opting out (player command)

You can personally opt out of AI-agent interaction at any time:

```
/agentoptout
```

Toggling it **on** means:

- agents will not greet you, whisper you, invite you to a party, or send you party chat;
- your map chat, whispers and party invites are ignored by agents;
- party search will not auto-invite you on an agent's behalf;
- agents will not follow you.

Run `/agentoptout` again to allow interaction. The choice is saved to the database and
survives restarts (as long as the operator ran the `agent_optout` migration, below).

> Opting out does not remove agents from the world and does not stop human players from
> chatting about them; it only stops agent-to-you interaction.

---

## 2. For server operators

### 2.1 Prerequisites

- A working Cosmic server (MySQL 8 + the four base scripts in `database/sql/`).
- Java 21 / Maven build (`mvn -q clean test`).
- (Optional) an OpenAI-compatible endpoint: OpenAI, Azure, or a local server such as
  Ollama, llama.cpp, vLLM or LM Studio. **If no endpoint is configured, agents use a
  deterministic scripted brain** — good for testing without any LLM cost.

### 2.2 Database migrations

Run these manually in order (see `database/sql/migration/README.md`):

| Migration | Creates | Needed for |
| --- | --- | --- |
| `v0.15.0.sql` | `agent_memory` | persistent agent memory |
| `v0.16.0.sql` | `agent_optout` | persistent player opt-out |

Agents still work without them; persistence just logs warnings.

### 2.3 `config.yaml` (`server:` section)

Minimum to enable agents:

```yaml
server:
  USE_LLM_AGENTS: true
  # Leave these blank to use the deterministic scripted brain:
  LLM_API_URL: "http://localhost:11434/v1/chat/completions"
  LLM_API_KEY: ""
  LLM_MODEL: "llama3.1"
```

A friendly starter profile (reactive chat + light combat assist + safety):

```yaml
server:
  USE_LLM_AGENTS: true
  LLM_AGENTS_MAX: 4
  LLM_AGENT_TICK_MS: 2000
  LLM_AGENT_PERCEPTION: "BOUNDED"
  LLM_AGENT_NAME_PREFIX: "[AI]"
  LLM_AGENT_CONTENT_FILTER: true
  LLM_AGENT_MAX_ACTIONS_PER_SEC: 4
  LLM_MAX_CALLS_PER_MIN: 60          # global LLM request budget (0 = unlimited)

  # Party / movement
  LLM_AGENT_FOLLOW: true
  LLM_AGENT_FOLLOW_WARP: false       # walk to a portal / warp when the leader leaves the map
  LLM_AGENT_FOLLOW_CHANNEL: false    # re-log the agent on the leader's channel (disruptive)

  # Combat assist
  LLM_AGENT_AUTO_SUSTAIN: true
  LLM_AGENT_AUTO_COMBAT: true
  LLM_AGENT_ATTACK_SKILL: 0          # 0 = basic attack; otherwise a learned skill id
  LLM_AGENT_AUTO_HEAL: true
  LLM_AGENT_AUTO_BUFF: true
  LLM_AGENT_AUTO_BUFF_SKILLS: ""
  LLM_AGENT_AUTO_LOOT: true
  LLM_AGENT_AUTO_FLEE: true
  LLM_AGENT_HP_POTION_PERCENT: 50
  LLM_AGENT_MP_POTION_PERCENT: 30

  # Optional, louder: proactive social behavior
  LLM_AGENT_PROACTIVE: false
  # LLM_AGENT_GREET: true
  # LLM_AGENT_MATCHMAKE: true
  # LLM_AGENT_NOTICE: true
```

Restart the server after changing `config.yaml`.

### 2.4 Prepare agent characters

Agents use **real** `accounts`/`characters` rows (party, chat and level all need a
character id). Create one or more characters in the desired world and log them out. By
convention name them with the `LLM_AGENT_NAME_PREFIX` (`[AI]`); set
`LLM_AGENT_REQUIRE_PREFIX: true` to enforce it.

### 2.5 Spawn and manage (GM, rank 5+)

```
/agent spawn <characterName>     # bring a logged-out character online as an agent
/agent list                      # show active agents
/agent status <name>             # brain, map, HP, follow target, queued events, last actions
/agent say <name> <message>      # GM instruction to the brain
/agent follow <name> <player>    # make an agent follow a player
/agent stopfollow <name>
/agent search <name>             # list the agent in party search now
/agent mute <name>               # suppress the agent's chat
/agent unmute <name>
/agent optout <player>           # toggle a player's opt-out for them
/agent despawn <name>            # take the agent offline
```

`/agent` work is offloaded off the netty event loop, so it never blocks the GM's connection.

### 2.6 Personas (optional)

Point `LLM_AGENT_PERSONAS_FILE` at a JSON or YAML file to give each agent a personality:

```json
{
  "Alice": {
    "description": "a cheerful cleric who loves helping new players",
    "systemPrompt": "You are Alice, a kind Cleric in MapleStory v83.",
    "greeting": "Hi {name}! Need a hand?",
    "traits": "cheerful, patient, helpful"
  }
}
```

```yaml
- name: Alice
  description: a cheerful cleric who loves helping new players
  greeting: "Hi {name}! Need a hand?"
  traits: cheerful, patient, helpful
```

- `description`/`systemPrompt` are used for the LLM system prompt.
- `greeting` (used by proactive greeting; `{name}` is replaced) and `traits` are optional.
- Lookup is case-insensitive and falls back to a generic persona.

---

## 3. Behavior reference

| Area | Behavior | Gated by |
| --- | --- | --- |
| Chat | Replies to map chat/whispers/party chat; LLM tool calling | `USE_LLM_AGENTS` |
| Party | Accept/deny invites, join/leave, party chat, party-search listing | `USE_LLM_AGENTS` |
| Follow | Follow leader/player; portal walk; warp fallback; channel re-login | `LLM_AGENT_FOLLOW`, `…_FOLLOW_WARP`, `…_FOLLOW_CHANNEL` |
| Combat | Basic + skill attacks (multi-target/multi-line), buffs, heal, potions, flee | `LLM_AGENT_AUTO_*` |
| Ammo | Recharge stars/bullets or buy arrows at a shop NPC (pays mesos) | `LLM_AGENT_AUTO_RECHARGE` |
| Loot | Picks up owned/party/FFA drops | `LLM_AGENT_AUTO_LOOT` |
| Proactive | Greet nearby players, auto party-search matchmaking, idle patrol | `LLM_AGENT_PROACTIVE` + sub-switches |
| Safety | Content filter, per-agent rate limit, global request budget, mute, opt-out | `LLM_AGENT_CONTENT_FILTER`, `…_MAX_ACTIONS_PER_SEC`, `LLM_MAX_CALLS_PER_MIN` |

Agents are **reactive by default**; proactive behavior only runs when you turn it on.
Movement is clamped waypoint stepping (they cannot teleport from hallucinated coordinates),
and all actions go through the real packet handlers, so normal validation still applies.

---

## 4. Safety & moderation

- **Name prefix** (`[AI]`) labels agents; `LLM_AGENT_REQUIRE_PREFIX` enforces it.
- **Content filter** masks a small blocklist and clamps message length; all chat headings
  (`/`, `@`, `!`) are stripped so an agent cannot run commands.
- **Rate limits** cap per-agent actions and globally cap LLM requests per minute.
- **`/agent mute`** silences an agent's chat without stopping its other behavior.
- **`/agentoptout`** gives every player a personal opt-out (persisted).
- Every agent utterance/action is audit-logged (`AgentActionDispatcher`).

---

## 5. Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| `/agent spawn` refuses | `USE_LLM_AGENTS` is false, name doesn't exist in that world, name prefix required, or the character/account is already online. |
| Agent spawned but says nothing | No `LLM_API_URL`/`LLM_MODEL` ⇒ scripted brain; or the LLM endpoint is unreachable (check logs). `/agent status` shows the brain in use. |
| Agent spams or is slow | Tune `LLM_AGENT_TICK_MS` and `LLM_MAX_CALLS_PER_MIN`; check the endpoint latency. |
| Memory/opt-out not saved | Run `v0.15.0.sql` / `v0.16.0.sql`; check server logs for persistence warnings. |
| Agent stuck / not moving | It may be following a target on another map/channel; enable `LLM_AGENT_FOLLOW_WARP` / `…_FOLLOW_CHANNEL`. Movement is clamped stepping, not a full pathfinder. |
| Players don't want agents | They can `/agentoptout`; use `/agent mute`, or disable `LLM_AGENT_PROACTIVE`, or turn the whole feature off. |

---

## 6. Current state & remaining work

**Done (M0–M4 + opt-out):** reactive chat/party/whisper, LLM tool calling, personas,
persistent memory + compaction, party membership and party-search matchmaking, follow
(same-map walking, portal-based cross-map travel, opt-in cross-channel re-login), combat
assist (basic/multi-target/multi-line skill attacks, buffs, heal, potions, flee, respawn,
loot, ammo resupply), player-facing opt-out, moderation/mute, observability (`/agent status`),
content filter, rate limits and a global LLM request budget.

**Remaining (minor):**

- **Pathing depth** — movement is clamped waypoint stepping with portal travel; there is no
  full foothold/rope pathfinder (the server models no ropes/ladders and is client-authoritative
  on position).
- **Combat fidelity** — Hurricane/Rapid Fire after-delay timing and cast-animation stance.
- `/agent tell` and `/agent pause` are not implemented (`mute`/`unmute` are).
