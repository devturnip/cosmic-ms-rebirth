package client.command.commands.gm0;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import client.Client;
import client.command.Command;
import client.command.CommandsExecutor;
import constants.id.NpcId;

public class SkillSetCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(CommandsExecutor.class);
    {
        setDescription("Test Command");
    }

    @Override
    public void execute(Client c, String[] params) {
        c.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "skillset");
    }
}
