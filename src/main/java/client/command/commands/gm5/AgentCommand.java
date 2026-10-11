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
        setDescription("Manage in-process LLM agents. Usage: /agent spawn|despawn|list|say ...");
    }

    @Override
    public void execute(Client c, String[] params) {
        // Spawning loads a character and runs the full login bootstrap; run it off the Netty
        // event loop so the GM's connection is not blocked.
        ThreadManager.getInstance().newTask(() -> executeInternal(c, params));
    }

    private void executeInternal(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        AgentManager manager = AgentManager.getInstance();

        if (params.length < 1) {
            player.message("Usage: /agent spawn <characterName> | despawn <characterName> | list | say <characterName> <message>");
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
            default -> player.message("Unknown agent action '" + action + "'. Use spawn, despawn, list or say.");
        }
    }
}
