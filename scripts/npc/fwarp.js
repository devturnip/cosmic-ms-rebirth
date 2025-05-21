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
            cm.sendGetText("Type in the name of the map you are looking for.");

        } else if (status == 1) {
            let search = cm.getText();
            
            const searchResults = MapIDNameSearch.getMapIDsByName(search);

            if (searchResults.length <= 0) {
                cm.sendPrev("No results found#k")
            } else {
                let sendStr = "";
                for (let i = 0; i<searchResults.length; i++) {
                    sendStr += "#L" + searchResults[i].getLeft() + "##b " + searchResults[i].getRight();
                    sendStr += "#k#l\r\n";
                }
                cm.sendSimple(sendStr)
            }
        } else if (status === 2) {
            cm.warp(selection)
            cm.dispose();
        } 
        
        else {
            cm.dispose();
        }
    }
}
