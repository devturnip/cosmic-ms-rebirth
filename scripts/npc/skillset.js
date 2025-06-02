const SkillFactory = Java.type('client.SkillFactory');

function start() {
    status = -1;
    const YamlConfig = Java.type('config.YamlConfig');
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 1) {
        status++;
    } else {
        status--;
    }
    if (status == 0) {
        const playerSkills = Array.from(cm.getPlayer().getSkills());
        let strbuilder = "#dThese are your learnt skills:\r\n";
        let count = 0;
        for (let i = 0; i < playerSkills.length; i++) {
            let skillLevel = String(playerSkills[i].getValue()).split(":")[0]
            let skill = playerSkills[i].getKey();
            let skillId = skill.getId();
            let action = skill.getAction();
            if (skillLevel > 0 && action) {
                strbuilder += "#L"+skillId+"##s" +skillId+ "# #b#q" +skillId+ "#-" +skillLevel+ "\t#l";
                count += 1;
            }
            if ( count % 2 === 0 && count !== 0 && action) {
                strbuilder += "\r\n";
            }
        }
        cm.sendSimple(strbuilder);
    } else if (status == 1) {
        print("selection: ", selection);
        cm.sendSimple("Which key do you want #s" +selection+ "# on? #b\r\n#L59#F1#L60#F2#L61#F3#L62#F4#L63#F5#L64#F6#L65#F7#L66#F8#L67#F9 \r\n #L68#F10#L87#F11#L88#F12 \r\n#L2#1#L3#2#L4#3#L5#4#L6#5#L7#6#L8#7#L9#8#L10#9#L11#0#L12#-#L13#= \r\n#L16#Q#L17#W#L18#E#L19#R#L20#T#L21#Y#L22#U#L23#I#L24#O#L25#P#L26#[#L27#] \r\n#L30#A#L31#S#L32#D#L33#F#L34#G#L35#H#L36#J#L37#K#L38#L#L39#;#L40#' \r\n#L42#Shift#L44#Z#L45#X#L46#C#L47#V#L48#B#L49#N#L50#M#L51#,#L52#.#L42#Shift \r\n#L29#Ctrl#L56#Alt#L57#SPACE#L56#Alt#L29#Ctrl \r\n#L82#Ins#L71#Hm#L73#Pup#L83#Del#L79#End#L81#Pdn");
        cm.dispose();
    }
    else {
        cm.dispose();
        return;
    }
}