package client.command.commands.gm0;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import client.Character;
import client.Client;
import client.command.Command;
import client.command.CommandsExecutor;
import constants.id.NpcId;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.mapletools.MapIDNameSearch;

public class FuzzyWarpCommand extends Command {
    private static Data mapStringData;
    private static final Logger log = LoggerFactory.getLogger(CommandsExecutor.class);
    {
        setDescription("Interactive warp, applies search and lets player choose.");
    }

    @Override
    public void execute(Client c, String[] params) {
        try {
            Character player = c.getPlayer();

            if (!player.isAlive()) {
                player.dropMessage(1, "This command cannot be used when you're dead.");
                return;
            }

            c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "fwarp");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
