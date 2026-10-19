package net.server.agent.harness;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.agent.AgentAction;
import net.server.agent.AgentSession;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.coordinator.matchchecker.MatchCheckerCoordinator;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance-verification tests for the guild / alliance / marriage / family actions:
 * {@code capture} / {@code verify} must report whether the server state actually changed,
 * mocking the same surfaces the real handlers mutate (mirroring
 * {@link HarnessInteractionVerifierTest}).
 */
class HarnessSocialVerifierTest {
    private final AgentSession session = Mockito.mock(AgentSession.class);

    /** A mocked character that passes the harness's null-map guards (and owns the session). */
    private Character mockCharacter(int id, String name) {
        Character chr = plainCharacter(id, name);
        Mockito.when(session.getPlayer()).thenReturn(chr);
        return chr;
    }

    /** A mocked character that is NOT wired to the session (targets, proposers, masters). */
    private Character plainCharacter(int id, String name) {
        Character chr = Mockito.mock(Character.class);
        Mockito.when(chr.getId()).thenReturn(id);
        Mockito.when(chr.getName()).thenReturn(name);
        Mockito.when(chr.getPosition()).thenReturn(new Point(1, 1));
        Mockito.when(chr.getMap()).thenReturn(Mockito.mock(MapleMap.class));
        Mockito.when(chr.getClient()).thenReturn(Mockito.mock(Client.class));
        return chr;
    }

    /** Places {@code chr} on {@code other}'s map so name resolution finds them. */
    private void coLocate(Character other, Character chr) {
        Mockito.when(other.getMap().getAllPlayers()).thenReturn(List.of(chr));
    }

    private Guild mockGuildWithMembers(Character chr, GuildCharacter... members) {
        Guild guild = Mockito.mock(Guild.class);
        Mockito.when(chr.getGuild()).thenReturn(guild);
        Mockito.when(guild.getMembers()).thenReturn(List.of(members));
        return guild;
    }

    private GuildCharacter mockMember(int id, String name, int rank) {
        GuildCharacter mgc = Mockito.mock(GuildCharacter.class);
        Mockito.when(mgc.getId()).thenReturn(id);
        Mockito.when(mgc.getName()).thenReturn(name);
        Mockito.when(mgc.getGuildRank()).thenReturn(rank);
        return mgc;
    }

    // ------------------------------------------------------------------
    // guild
    // ------------------------------------------------------------------

    @Test
    void guildCreateVerifiesViaMatchConfirmation() {
        Character chr = mockCharacter(1, "Agent-A");
        World world = Mockito.mock(World.class);
        MatchCheckerCoordinator coordinator = Mockito.mock(MatchCheckerCoordinator.class);
        Mockito.when(chr.getWorldServer()).thenReturn(world);
        Mockito.when(world.getMatchCheckerCoordinator()).thenReturn(coordinator);
        Mockito.when(coordinator.getMatchConfirmationLeaderid(1)).thenReturn(-1);

        AgentAction.GuildCreate action = new AgentAction.GuildCreate("Cosmonauts");
        Object pre = HarnessVerifier.capture(session, action);
        assertTrue(pre instanceof Long l && l == -1L, "no confirmation pre-create");

        Mockito.when(coordinator.getMatchConfirmationLeaderid(1)).thenReturn(1); // leader registered
        assertTrue(HarnessVerifier.verify(session, action, pre));
        assertFalse(HarnessVerifier.verify(session, action, 1L), "nothing changed");
    }

    @Test
    void guildCreateAnswerVerifiesOnGuildFounded() {
        Character chr = mockCharacter(2, "Agent-B");
        World world = Mockito.mock(World.class);
        MatchCheckerCoordinator coordinator = Mockito.mock(MatchCheckerCoordinator.class);
        Mockito.when(chr.getWorldServer()).thenReturn(world);
        Mockito.when(world.getMatchCheckerCoordinator()).thenReturn(coordinator);
        Mockito.when(coordinator.getMatchConfirmationLeaderid(2)).thenReturn(1);
        Mockito.when(chr.getGuildId()).thenReturn(0);

        AgentAction.GuildCreateAnswer action = new AgentAction.GuildCreateAnswer(true);
        Object pre = HarnessVerifier.capture(session, action);

        // Not the last co-founder to answer: the guild is not founded yet — not observable.
        assertNull(HarnessVerifier.verify(session, action, pre));

        // Last acceptance: the guild is founded, the member enters it.
        Mockito.when(chr.getGuildId()).thenReturn(7);
        assertTrue(HarnessVerifier.verify(session, action, pre));
        assertFalse(HarnessVerifier.verify(session, action, new long[]{-1, 0}), "no pending confirmation");
    }

    @Test
    void guildCreateAnswerDenyVerifiesViaDismissedConfirmation() {
        Character chr = mockCharacter(2, "Agent-B");
        World world = Mockito.mock(World.class);
        MatchCheckerCoordinator coordinator = Mockito.mock(MatchCheckerCoordinator.class);
        Mockito.when(chr.getWorldServer()).thenReturn(world);
        Mockito.when(world.getMatchCheckerCoordinator()).thenReturn(coordinator);
        Mockito.when(coordinator.getMatchConfirmationLeaderid(2)).thenReturn(1);
        Mockito.when(chr.getGuildId()).thenReturn(0);

        AgentAction.GuildCreateAnswer action = new AgentAction.GuildCreateAnswer(false);
        Object pre = HarnessVerifier.capture(session, action);
        Mockito.when(coordinator.getMatchConfirmationLeaderid(2)).thenReturn(-1); // dismissed
        assertTrue(HarnessVerifier.verify(session, action, pre));
    }

    @Test
    void guildInviteVerifiesViaPendingInvite() {
        Character chr = mockCharacter(1, "Agent-A");
        Character target = plainCharacter(2, "Agent-B");
        coLocate(chr, target);

        AgentAction.GuildInvite action = new AgentAction.GuildInvite("Agent-B");
        Object pre = HarnessVerifier.capture(session, action);
        assertFalse((Boolean) pre, "no invite pre-dispatch");

        // What Guild.sendInvitation does on acceptance of the packet:
        InviteCoordinator.createInvite(InviteType.GUILD, chr, 7, 2);
        assertTrue(HarnessVerifier.verify(session, action, pre));
        InviteCoordinator.removeInvite(InviteType.GUILD, 2);
    }

    @Test
    void guildJoinLeaveAndExpelVerifyViaMembership() {
        Character chr = mockCharacter(1, "Agent-A");
        AgentAction.GuildAcceptInvite accept = new AgentAction.GuildAcceptInvite("Agent-A");

        Object pre = HarnessVerifier.capture(session, accept);
        Mockito.when(chr.getGuildId()).thenReturn(7);
        assertTrue(HarnessVerifier.verify(session, accept, pre));
        assertFalse(HarnessVerifier.verify(session, accept, 7L), "already in a guild");

        AgentAction.GuildLeave leave = new AgentAction.GuildLeave("Agent-A");
        Object leavePre = HarnessVerifier.capture(session, leave);
        Mockito.when(chr.getGuildId()).thenReturn(0);
        assertTrue(HarnessVerifier.verify(session, leave, leavePre));
        assertFalse(HarnessVerifier.verify(session, leave, 0L), "was never in a guild");

        // Expel: member 42 leaves the roster (rank 1/2 initiators only).
        GuildCharacter member = mockMember(42, "Agent-B", 5);
        mockGuildWithMembers(chr, member);
        AgentAction.GuildExpel expel = new AgentAction.GuildExpel(42, "Agent-B");
        Object expelPre = HarnessVerifier.capture(session, expel);
        assertTrue((Boolean) expelPre);
        Mockito.when(chr.getGuild().getMembers()).thenReturn(List.of());
        assertTrue(HarnessVerifier.verify(session, expel, expelPre));
        assertFalse(HarnessVerifier.verify(session, expel, Boolean.FALSE), "target was never a member");
    }

    @Test
    void guildRankTitleAndNoticeVerifyViaGuildState() {
        Character chr = mockCharacter(1, "Agent-A");
        Guild guild = mockGuildWithMembers(chr, mockMember(42, "Agent-B", 5));
        Mockito.when(guild.getRankTitle(1)).thenReturn("Master");
        Mockito.when(guild.getRankTitle(2)).thenReturn("Jr Master");
        Mockito.when(guild.getRankTitle(3)).thenReturn("Member");
        Mockito.when(guild.getRankTitle(4)).thenReturn("Officer");
        Mockito.when(guild.getRankTitle(5)).thenReturn("Rookie");
        Mockito.when(guild.getNotice()).thenReturn("hello");

        AgentAction.GuildChangeRankTitle titles = new AgentAction.GuildChangeRankTitle(
                List.of("Master", "Jr Master", "Member", "Officer", "Legend"));
        Object titlesPre = HarnessVerifier.capture(session, titles);
        Mockito.when(guild.getRankTitle(5)).thenReturn("Legend");
        assertTrue(HarnessVerifier.verify(session, titles, titlesPre));
        Object titlesPost = HarnessVerifier.capture(session, titles); // same title, fresh snapshot
        assertFalse(HarnessVerifier.verify(session, titles, titlesPost), "titles unchanged");

        AgentAction.GuildChangeRank rank = new AgentAction.GuildChangeRank(42, 3);
        Object rankPre = HarnessVerifier.capture(session, rank);
        GuildCharacter promoted = mockMember(42, "Agent-B", 3);
        Mockito.when(guild.getMembers()).thenReturn(List.of(promoted));
        assertTrue(HarnessVerifier.verify(session, rank, rankPre));
        Object rankPost = HarnessVerifier.capture(session, rank); // same rank, fresh snapshot
        assertFalse(HarnessVerifier.verify(session, rank, rankPost), "rank unchanged");

        AgentAction.GuildNotice notice = new AgentAction.GuildNotice("bye");
        Object noticePre = HarnessVerifier.capture(session, notice);
        Mockito.when(guild.getNotice()).thenReturn("bye");
        assertTrue(HarnessVerifier.verify(session, notice, noticePre));
        Object noticePost = HarnessVerifier.capture(session, notice); // same notice, fresh snapshot
        assertFalse(HarnessVerifier.verify(session, notice, noticePost), "notice unchanged");
    }

    // ------------------------------------------------------------------
    // alliance
    // ------------------------------------------------------------------

    @Test
    void allianceInviteVerifiesViaPendingInviteOnGuildMaster() throws Exception {
        Character chr = mockCharacter(1, "Agent-A");
        Character master = plainCharacter(7, "Agent-Master");
        GuildCharacter masterEntry = mockMember(7, "Agent-Master", 1);
        Mockito.when(masterEntry.getCharacter()).thenReturn(master);

        // Register a (mocked) target guild on the server so the verifier can resolve its master.
        Guild guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getName()).thenReturn("Cosmonauts");
        Mockito.when(guild.getLeaderId()).thenReturn(7);
        Mockito.when(guild.getMGC(7)).thenReturn(masterEntry);
        Map<Integer, Guild> guilds = serverGuildsMap();
        guilds.put(99, guild);
        try {
            AgentAction.AllianceInvite action = new AgentAction.AllianceInvite("Cosmonauts");
            Object pre = HarnessVerifier.capture(session, action);
            assertFalse((Boolean) pre, "no invite pre-dispatch");

            // What Alliance.sendInvitation does on acceptance of the packet:
            InviteCoordinator.createInvite(InviteType.ALLIANCE, chr, 1, 7);
            assertTrue(HarnessVerifier.verify(session, action, pre));
        } finally {
            guilds.remove(99);
            InviteCoordinator.removeInvite(InviteType.ALLIANCE, 7);
        }
    }

    @Test
    void allianceJoinAndLeaveVerifyViaAllianceRegistration() {
        Character chr = mockCharacter(1, "Agent-A");
        Guild guild = Mockito.mock(Guild.class);
        Mockito.when(chr.getGuild()).thenReturn(guild);
        Mockito.when(chr.getGuildId()).thenReturn(7);
        Mockito.when(guild.getAllianceId()).thenReturn(0);

        AgentAction.AllianceAcceptInvite accept = new AgentAction.AllianceAcceptInvite("Agent-A");
        Object pre = HarnessVerifier.capture(session, accept);
        Mockito.when(guild.getAllianceId()).thenReturn(4);
        assertTrue(HarnessVerifier.verify(session, accept, pre));
        assertFalse(HarnessVerifier.verify(session, accept, 4L), "guild not registered");

        AgentAction.AllianceLeave leave = new AgentAction.AllianceLeave();
        Object leavePre = HarnessVerifier.capture(session, leave);
        Mockito.when(guild.getAllianceId()).thenReturn(0);
        assertTrue(HarnessVerifier.verify(session, leave, leavePre));
    }

    // ------------------------------------------------------------------
    // marriage
    // ------------------------------------------------------------------

    @Test
    void engageVerifiesViaReservedMarriageItem() {
        Character chr = mockCharacter(1, "Agent-A");
        Mockito.when(chr.getMarriageItemId()).thenReturn(0);

        AgentAction.Engage action = new AgentAction.Engage("Agent-B", 2240000);
        Object pre = HarnessVerifier.capture(session, action);
        Mockito.when(chr.getMarriageItemId()).thenReturn(2240000); // what sendEngageProposal sets
        assertTrue(HarnessVerifier.verify(session, action, pre));
        assertFalse(HarnessVerifier.verify(session, action, 2240000L), "already engaging");
        Mockito.when(chr.getMarriageItemId()).thenReturn(0); // proposal refused
        assertFalse(HarnessVerifier.verify(session, action, 0L), "proposal refused");
    }

    @Test
    void engageAnswerVerifiesViaPartnerId() {
        Character proposer = plainCharacter(1, "Agent-A");
        Character chr = mockCharacter(2, "Agent-B");
        coLocate(chr, proposer);
        Mockito.when(chr.getPartnerId()).thenReturn(-1);
        Mockito.when(proposer.getMarriageItemId()).thenReturn(2240000); // pending proposal

        AgentAction.EngageAnswer accept = new AgentAction.EngageAnswer(true, "Agent-A");
        Object pre = HarnessVerifier.capture(session, accept);
        Mockito.when(chr.getPartnerId()).thenReturn(1); // what the handler sets on acceptance
        assertTrue(HarnessVerifier.verify(session, accept, pre));
        assertFalse(HarnessVerifier.verify(session, accept, new long[]{5, 2240000}), "already engaged");

        AgentAction.EngageAnswer deny = new AgentAction.EngageAnswer(false, "Agent-A");
        Object denyPre = HarnessVerifier.capture(session, deny);
        Mockito.when(proposer.getMarriageItemId()).thenReturn(-1); // the handler withdraws the proposal
        assertTrue(HarnessVerifier.verify(session, deny, denyPre));
        assertFalse(HarnessVerifier.verify(session, deny, new long[]{-1, -1}), "no proposal was pending");
    }

    @Test
    void breakEngagementVerifiesViaSeveredPartnerId() {
        Character chr = mockCharacter(1, "Agent-A");
        Mockito.when(chr.getPartnerId()).thenReturn(2);

        AgentAction.BreakEngagement action = new AgentAction.BreakEngagement(4031357);
        Object pre = HarnessVerifier.capture(session, action);
        Mockito.when(chr.getPartnerId()).thenReturn(-1);
        assertTrue(HarnessVerifier.verify(session, action, pre));
        assertFalse(HarnessVerifier.verify(session, action, 0L), "no relationship to sever");
    }

    // ------------------------------------------------------------------
    // family
    // ------------------------------------------------------------------

    @Test
    void familyInviteVerifiesViaPendingInvite() {
        Character chr = mockCharacter(1, "Agent-A");
        Character target = plainCharacter(2, "Agent-B");
        coLocate(chr, target);

        AgentAction.FamilyAdd action = new AgentAction.FamilyAdd("Agent-B");
        Object pre = HarnessVerifier.capture(session, action);
        assertFalse((Boolean) pre, "no invite pre-dispatch");

        // What FamilyAddHandler does on acceptance of the packet:
        InviteCoordinator.createInvite(InviteType.FAMILY, chr, 0, 2);
        assertTrue(HarnessVerifier.verify(session, action, pre));
        InviteCoordinator.removeInvite(InviteType.FAMILY, 2);
    }

    @Test
    void familyAcceptVerifiesViaFamilyRegistration() {
        Character chr = mockCharacter(2, "Agent-B");
        Mockito.when(chr.getFamily()).thenReturn(null);

        AgentAction.FamilyAccept accept = new AgentAction.FamilyAccept(true, "Agent-A");
        Object pre = HarnessVerifier.capture(session, accept);
        client.Family family = Mockito.mock(client.Family.class);
        Mockito.when(family.getID()).thenReturn(3);
        Mockito.when(chr.getFamily()).thenReturn(family); // what AcceptFamilyHandler builds
        assertTrue(HarnessVerifier.verify(session, accept, pre));
        assertFalse(HarnessVerifier.verify(session, accept, 3L), "family unchanged");

        AgentAction.FamilyAccept deny = new AgentAction.FamilyAccept(false, "Agent-A");
        assertNull(HarnessVerifier.verify(session, deny, -1L), "denials only notify the inviter");
    }

    /** The server singleton's live guild registry (mocked entries are removed after use). */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Guild> serverGuildsMap() throws Exception {
        java.lang.reflect.Field field = Server.class.getDeclaredField("guilds");
        field.setAccessible(true);
        return (Map<Integer, Guild>) field.get(Server.getInstance());
    }
}
