/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client.command.commands.gm5;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.agent.AgentBrainFactory;
import net.server.agent.AgentConfig;
import net.server.agent.AgentManager;
import net.server.agent.AgentSession;
import server.ThreadManager;

public class AgentCommand extends Command {
    {
        setDescription("Manage in-process LLM agents. Usage: /agent spawn|despawn|list|status|say|follow|stopfollow|search|mute|unmute ...");
    }

    @Override
    public void execute(Client c, String[] params) {
        // Spawning loads a character and runs the full login bootstrap; run it off the Netty
        // event loop so the GM's connection is not blocked.
        ThreadManager.getInstance().newTask(() -> executeInternal(c, params));
    }

    private static AgentSession findSession(AgentManager manager, String name) {
        for (AgentSession session : manager.getSessions()) {
            if (session.getName().equalsIgnoreCase(name)) {
                return session;
            }
        }
        return null;
    }

    private void executeInternal(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        AgentManager manager = AgentManager.getInstance();

        if (params.length < 1) {
            player.message("Usage: /agent spawn <name> | despawn <name> | list | status <name> | say <name> <msg> | follow <name> <player> | stopfollow <name> | search <name> | mute <name> | unmute <name>");
            return;
        }

        String action = params[0].toLowerCase();
        switch (action) {
            case "spawn" -> {
                if (!AgentConfig.enabled()) {
                    player.message("LLM agents are disabled. Set USE_LLM_AGENTS: true in config.yaml and restart.");
                    return;
                }
                if (params.length < 2) {
                    player.message("Usage: /agent spawn <characterName>");
                    return;
                }
                String name = params[1];
                AgentSession session = manager.spawn(name, c.getWorld(), c.getChannel(), AgentBrainFactory.create(name));
                if (session == null) {
                    player.message("Failed to spawn agent '" + name + "'. Check server logs (name must match an existing character in this world).");
                } else {
                    player.message("Spawned agent '" + session.getName() + "' with brain " + session.getBrain().describe() + ".");
                }
            }
            case "despawn" -> {
                if (params.length < 2) {
                    player.message("Usage: /agent despawn <characterName>");
                    return;
                }
                String name = params[1];
                player.message(manager.despawn(name) ? "Despawned agent '" + name + "'." : "No active agent named '" + name + "'.");
            }
            case "list" -> {
                var sessions = manager.getSessions();
                if (sessions.isEmpty()) {
                    player.message("No active agents.");
                    return;
                }
                StringBuilder sb = new StringBuilder("Active agents (").append(sessions.size()).append("):");
                for (AgentSession session : sessions) {
                    sb.append("\r\n  ").append(session.getName())
                            .append(" chr=").append(session.getCharacterId())
                            .append(" brain=").append(session.getBrain().describe())
                            .append(" queued=").append(session.getQueuedEvents());
                }
                player.message(sb.toString());
            }
            case "say" -> {
                if (params.length < 3) {
                    player.message("Usage: /agent say <characterName> <message>");
                    return;
                }
                String name = params[1];
                String message = joinStringFrom(params, 2);
                player.message(manager.instruct(name, message) ? "Sent instruction to '" + name + "'." : "No active agent named '" + name + "'.");
            }
            case "follow" -> {
                if (params.length < 3) {
                    player.message("Usage: /agent follow <agentName> <playerName>");
                    return;
                }
                player.message(manager.setFollow(params[1], params[2], c.getWorld())
                        ? "Agent '" + params[1] + "' will follow '" + params[2] + "'."
                        : "Failed to set follow (check both names in this world).");
            }
            case "stopfollow" -> {
                if (params.length < 2) {
                    player.message("Usage: /agent stopfollow <agentName>");
                    return;
                }
                player.message(manager.clearFollow(params[1]) ? "Agent '" + params[1] + "' will stop following." : "No active agent named '" + params[1] + "'.");
            }
            case "search" -> {
                if (params.length < 2) {
                    player.message("Usage: /agent search <agentName>");
                    return;
                }
                player.message(manager.registerForPartySearch(params[1], c.getWorld())
                        ? "Agent '" + params[1] + "' is now listed in party search."
                        : "Failed to register agent '" + params[1] + "' for party search (no active agent?).");
            }
            case "status" -> {
                if (params.length < 2) {
                    player.message("Usage: /agent status <agentName>");
                    return;
                }
                AgentSession session = findSession(manager, params[1]);
                if (session == null) {
                    player.message("No active agent named '" + params[1] + "'.");
                    return;
                }
                Character agent = session.getPlayer();
                long age = session.getLastDecisionAt() <= 0 ? -1 : System.currentTimeMillis() - session.getLastDecisionAt();
                StringBuilder sb = new StringBuilder("Agent ").append(session.getName())
                        .append(" chr=").append(session.getCharacterId())
                        .append(" brain=").append(session.getBrain().describe())
                        .append(" active=").append(session.isActive())
                        .append(" muted=").append(session.isMuted())
                        .append(" queued=").append(session.getQueuedEvents())
                        .append(" follow=").append(session.getFollowTargetId())
                        .append(session.isManualFollow() ? " (manual)" : "");
                if (agent != null) {
                    sb.append(" map=").append(agent.getMapId())
                            .append(" lvl=").append(agent.getLevel())
                            .append(" hp=").append(agent.getHp()).append('/').append(agent.getCurrentMaxHp());
                }
                sb.append(" lastDecision=").append(age < 0 ? "never" : age + "ms ago")
                        .append(" lastActions=").append(session.getLastActions());
                player.message(sb.toString());
            }
            case "mute", "unmute" -> {
                if (params.length < 2) {
                    player.message("Usage: /agent mute|unmute <agentName>");
                    return;
                }
                AgentSession session = findSession(manager, params[1]);
                if (session == null) {
                    player.message("No active agent named '" + params[1] + "'.");
                    return;
                }
                boolean mute = action.equals("mute");
                if (session.isMuted() != mute) {
                    session.toggleMuted();
                }
                player.message("Agent '" + session.getName() + "' is now "
                        + (session.isMuted() ? "muted" : "unmuted") + ".");
            }
            default -> player.message("Unknown agent action '" + action + "'. Use spawn, despawn, list, status, say, follow, stopfollow, search, mute or unmute.");
        }
    }
}
