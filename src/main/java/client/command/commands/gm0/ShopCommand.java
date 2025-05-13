

package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.ShopFactory;

public class ShopCommand extends Command {
    {
        setDescription("Open player general shop.");
    }

    @Override
    public void execute(Client c, String[] params){
        ShopFactory.getInstance().getShop(12345).sendShop(c);
    }
}