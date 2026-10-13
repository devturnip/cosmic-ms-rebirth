package net.server.agent;

import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forms or extends a party so a summoned companion joins the requester. The class is a plain
 * seam: it has no DB/static state, so tests can mock or subclass it.
 * <p>
 * Failure is always non-fatal — the companion still spawns and follows; it just does not get a
 * party slot.
 */
public class CompanionPartyService {
    private static final Logger log = LoggerFactory.getLogger(CompanionPartyService.class);

    /**
     * Puts the companion in the player's party, creating the party first when the player is solo.
     *
     * @return whether the companion ended up in a party
     */
    public boolean autoParty(Character player, AgentSession agent) {
        if (!AgentConfig.autoParty() || player == null || agent == null) {
            return false;
        }
        final Character companion = agent.getPlayer();
        if (companion == null) {
            return false;
        }
        try {
            Party party = player.getParty();
            if (party == null) {
                // Below the starter party gate the companion simply follows without a party.
                if (player.getLevel() < 10 && !YamlConfig.config.server.USE_PARTY_FOR_STARTERS) {
                    return false;
                }
                if (!Party.createParty(player, true)) {
                    return false;
                }
                party = player.getParty();
            }
            if (party == null || party.getMembers().size() >= 6) {
                return false;
            }
            return Party.joinParty(companion, party.getId(), true);
        } catch (Throwable t) {
            log.warn("Failed to auto-party companion {} with player {}", agent.getName(), player.getName(), t);
            return false;
        }
    }
}
