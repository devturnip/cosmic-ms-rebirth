/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.agent.AgentCompanionService;
import server.ThreadManager;

/**
 * Player-facing AI companion command: summon, list and dismiss ephemeral companions.
 * <p>
 * Provisioning hits the database, so the work is offloaded to {@link ThreadManager} and never
 * runs on the Netty event loop.
 */
public class AgentCompanionCommand extends Command {
    {
        setDescription("Summon or dismiss an AI companion. Usage: /agents list | find [role] | dismiss");
    }

    @Override
    public void execute(Client c, String[] params) {
        ThreadManager.getInstance().newTask(() -> executeInternal(c, params));
    }

    private void executeInternal(Client c, String[] params) {
        final Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        if (params.length < 1) {
            player.message("Usage: /agents list | find [role] | dismiss");
            return;
        }

        final AgentCompanionService service = ServiceHolder.INSTANCE;
        switch (params[0].toLowerCase()) {
            case "list" -> {
                for (String line : service.describeAvailable(player)) {
                    player.message(line);
                }
            }
            case "find" -> {
                final String role = params.length >= 2 ? params[1] : null;
                player.message(service.find(player, role).message());
            }
            case "dismiss" -> player.message(service.dismiss(player).message());
            default -> player.message("Usage: /agents list | find [role] | dismiss");
        }
    }

    /** Lazy holder so the service (and its cooldown state) is shared across invocations. */
    private static final class ServiceHolder {
        private static final AgentCompanionService INSTANCE = AgentCompanionService.createDefault();
    }
}
