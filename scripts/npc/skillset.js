const SkillFactory = Java.type('client.SkillFactory');

function start() {
    status = -1;
    const YamlConfig = Java.type('config.YamlConfig');
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode === 1) {
        status++;
        const playerSkills = Array.from(cm.getPlayer().getSkills().keySet());
        const skillMap = new Map(cm.getPlayer().getSkills())
        for (let i = 0; i < playerSkills.length; i++) {
            print("skills: " + SkillFactory.getSkillName(playerSkills[i].getId()));
        }
        // print("skillMap:", [...skillMap.entries()]);
        cm.dispose();
    } else {
        cm.dispose();
        return;
    }
}