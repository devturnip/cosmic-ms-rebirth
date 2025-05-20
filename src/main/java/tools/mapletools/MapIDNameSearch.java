package tools.mapletools;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;

public class MapIDNameSearch {
    private static final Logger log = LoggerFactory.getLogger(MapIDNameSearch.class);
    private static Data mapStringData = DataProviderFactory.getDataProviderString().getData("Map.img");

    public static Data getAllMapData() {
        return mapStringData;
    }

    public static ArrayList<String> getMapIDsByName(String search) {
        ArrayList<String> searchResults = new ArrayList<String>();
        String mapName, streetName;
        for (Data searchDataDir : mapStringData.getChildren()) {
            for (Data searchData : searchDataDir.getChildren()) {
                mapName = DataTool.getString(searchData.getChildByPath("mapName"), "NO-NAME");
                streetName = DataTool.getString(searchData.getChildByPath("streetName"), "NO-NAME");
                if (mapName.toLowerCase().contains(search.toLowerCase())
                        || streetName.toLowerCase().contains(search.toLowerCase())) {
                    searchResults.add(streetName + "-" + mapName);
                }
            }
        }
        return searchResults;
    }

}
