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
import net.server.agent.AgentManager;
import server.ThreadManager;

/**
 * Player-facing opt-out from AI-agent interaction: no greetings, party-search invites, whispers
 * or party invites from agents while enabled. The state is persisted per character.
 */
public class AgentOptoutCommand extends Command {
    {
        setDescription("Toggle opting out of AI-agent greetings/invites.");
    }

    @Override
    public void execute(Client c, String[] params) {
        // Persisting the opt-out hits the database; keep it off the Netty event loop.
        ThreadManager.getInstance().newTask(() -> executeInternal(c));
    }

    private void executeInternal(Client c) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        AgentManager manager = AgentManager.getInstance();
        boolean optOut = !manager.isOptedOut(player.getId());
        manager.setOptedOut(player.getId(), optOut);
        player.message(optOut
                ? "AI agents will no longer greet, invite or whisper you."
                : "AI agents may interact with you again.");
    }
}
