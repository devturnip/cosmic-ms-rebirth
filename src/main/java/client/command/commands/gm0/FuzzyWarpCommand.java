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
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;

public class FuzzyWarpCommand extends Command {
    private static Data mapStringData;
    private static final Logger log = LoggerFactory.getLogger(CommandsExecutor.class);
    {
        setDescription("Warp using names, applies search and lets player choose.");

        DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
        mapStringData = dataProvider.getData("Map.img");
    }

    @Override
    public void execute(Client c, String[] params) {
        try {
            Character player = c.getPlayer();
            if (params.length < 1) {
                player.yellowMessage("Syntax: !fwarp <mapname>");
                return;
            }

            if (!player.isAlive()) {
                player.dropMessage(1, "This command cannot be used when you're dead.");
                return;
            }

            StringBuilder sb = new StringBuilder();

            String search = joinStringFrom(params, 0);
            long start = System.currentTimeMillis();// for the lulz

            Data data = mapStringData;
            String mapName, streetName;
            log.info("Chr {} fuzzy warping {}", c.getPlayer().getName(), search);

            for (Data searchDataDir : data.getChildren()) {
                int index = 0;
                for (Data searchData : searchDataDir.getChildren()) {
                    mapName = DataTool.getString(searchData.getChildByPath("mapName"), "NO-NAME");
                    streetName = DataTool.getString(searchData.getChildByPath("streetName"), "NO-NAME");

                    if (mapName.toLowerCase().contains(search.toLowerCase())
                            || streetName.toLowerCase().contains(search.toLowerCase())) {
                        sb.append("#L" + index + "#").append("#b").append(Integer.parseInt(searchData.getName()))
                                .append("#k - #r")
                                .append(streetName).append(" - ").append(mapName).append("\r\n");
                    }
                    index += 1;
                } 
            }
            if (sb.length() == 0) {
                sb.append("#bNo ").append(params[0].toLowerCase()).append("s found.\r\n");
            }
            // sb.append("\r\n#kLoaded within ").append((double) (System.currentTimeMillis()
            // - start) / 1000)
            // .append(" seconds.");// because I can, and it's free

            // c.getAbstractPlayerInteraction().npcTalk(NpcId.MAPLE_ADMINISTRATOR,
            // sb.toString());
            sb.append("#k#l");
            c.getAbstractPlayerInteraction().npcAsk(NpcId.MAPLE_ADMINISTRATOR, sb.toString(), (byte) 0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
