/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.PlayerLoginService;
import net.server.Server;
import net.server.channel.Channel;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import net.server.world.World;
import service.NoteService;
import tools.PacketCreator;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerLoggedinHandler extends AbstractPacketHandler {
    private static final Set<Integer> attemptingLoginAccounts = new HashSet<>();

    private final NoteService noteService;

    public PlayerLoggedinHandler(NoteService noteService) {
        this.noteService = noteService;
    }

    private boolean tryAcquireAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            if (attemptingLoginAccounts.contains(accId)) {
                return false;
            }

            attemptingLoginAccounts.add(accId);
            return true;
        }
    }

    private void releaseAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            attemptingLoginAccounts.remove(accId);
        }
    }

    @Override
    public final boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        final int cid = p.readInt(); // TODO: investigate if this is the "client id" supplied in PacketCreator#getServerIP()
        final Server server = Server.getInstance();

        if (!c.tryacquireClient()) {
            // thanks MedicOP for assisting on concurrency protection here
            c.sendPacket(PacketCreator.getAfterLoginError(10));
        }

        try {
            World wserv = server.getWorld(c.getWorld());
            if (wserv == null) {
                c.disconnect(true, false);
                return;
            }

            Channel cserv = wserv.getChannel(c.getChannel());
            if (cserv == null) {
                c.setChannel(1);
                cserv = wserv.getChannel(c.getChannel());

                if (cserv == null) {
                    c.disconnect(true, false);
                    return;
                }
            }

            Character player = wserv.getPlayerStorage().getCharacterById(cid);

            final Hwid hwid;
            if (player == null) {
                hwid = SessionCoordinator.getInstance().pickLoginSessionHwid(c);
                if (hwid == null) {
                    c.disconnect(true, false);
                    return;
                }
            } else {
                hwid = player.getClient().getHwid();
            }

            c.setHwid(hwid);

            if (!server.validateCharacteridInTransition(c, cid)) {
                c.disconnect(true, false);
                return;
            }

            boolean newcomer = false;
            if (player == null) {
                try {
                    player = Character.loadCharFromDB(cid, c, true);
                    newcomer = true;
                } catch (SQLException e) {
                    e.printStackTrace();
                }

                if (player == null) { //If you are still getting null here then please just uninstall the game >.>, we dont need you fucking with the logs
                    c.disconnect(true, false);
                    return;
                }
            }
            c.setPlayer(player);
            c.setAccID(player.getAccountID());

            boolean allowLogin = true;

                /*  is this check really necessary?
                if (state == Client.LOGIN_SERVER_TRANSITION || state == Client.LOGIN_NOTLOGGEDIN) {
                    List<String> charNames = c.loadCharacterNames(c.getWorld());
                    if(!newcomer) {
                        charNames.remove(player.getName());
                    }

                    for (String charName : charNames) {
                        if(wserv.getPlayerStorage().getCharacterByName(charName) != null) {
                            allowLogin = false;
                            break;
                        }
                    }
                }
                */

            int accId = c.getAccID();
            if (tryAcquireAccount(accId)) { // Sync this to prevent wrong login state for double loggedin handling
                try {
                    int state = c.getLoginState();
                    if (state != Client.LOGIN_SERVER_TRANSITION || !allowLogin) {
                        c.setPlayer(null);
                        c.setAccID(0);

                        if (state == Client.LOGIN_LOGGEDIN) {
                            c.disconnect(true, false);
                        } else {
                            c.sendPacket(PacketCreator.getAfterLoginError(7));
                        }

                        return;
                    }
                    c.updateLoginState(Client.LOGIN_LOGGEDIN);
                } finally {
                    releaseAccount(accId);
                }
            } else {
                c.setPlayer(null);
                c.setAccID(0);
                c.sendPacket(PacketCreator.getAfterLoginError(10));
                return;
            }

            if (!newcomer) {
                c.setLanguage(player.getClient().getLanguage());
                c.setCharacterSlots((byte) player.getClient().getCharacterSlots());
                player.newClient(c);
            }

            PlayerLoginService.finishLogin(c, player, newcomer, noteService);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.releaseClient();
        }
    }
}
