/* @Author Ronan
 * @Author Vcoc
        Name: Steward
        Map(s): Foyer
        Info: Commands
        Script: commands.js
*/

var status;
const MapIDNameSearch = Java.type('tools.mapletools.MapIDNameSearch');

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            cm.sendGetText("Type in the name of the map you are looking for");

        } else if (status == 1) {
            let search = cm.getText();
            print("search: " + search);
            const searchResults = MapIDNameSearch.getMapIDsByName(search);
            print("search results: " + searchResults);

            cm.dispose()
        } else {
            cm.dispose();
        }
    }
}
