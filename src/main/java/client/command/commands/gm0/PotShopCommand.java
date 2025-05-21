

package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.ShopFactory;

public class PotShopCommand extends Command {
    {
        setDescription("Open player potion shop.");
    }

    @Override
    public void execute(Client c, String[] params){
        ShopFactory.getInstance().getShop(9201060).sendShop(c);
    }
}