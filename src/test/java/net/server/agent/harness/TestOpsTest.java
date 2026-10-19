package net.server.agent.harness;

import client.Character;
import client.Client;
import net.server.agent.AgentSession;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.life.Monster;
import server.maps.MapManager;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the setup ops' new capabilities: exact-position teleport (x/y) and the
 * kill-with-drops damage-source registration that feeds USE_SPAWN_RELEVANT_LOOT.
 * <p>
 * These tests require the harness to be enabled in the loaded config (the committed
 * config.yaml ships with {@code USE_TEST_HARNESS: true}); when disabled they skip.
 */
class TestOpsTest {

    private static final int MAP_ID = 103000802;

    private static boolean enabled() {
        return TestHarnessConfig.enabled();
    }

    private static AgentSession sessionOn(Character chr) {
        AgentSession session = Mockito.mock(AgentSession.class);
        when(session.getPlayer()).thenReturn(chr);
        return session;
    }

    /** A character whose client can resolve {@code getMap(mapId)} to {@code target}. */
    private static Character characterResolving(MapleMap target) {
        MapManager mapFactory = Mockito.mock(MapManager.class);
        when(mapFactory.getMap(anyInt())).thenReturn(target);
        Channel channel = Mockito.mock(Channel.class);
        when(channel.getMapFactory()).thenReturn(mapFactory);
        Client client = Mockito.mock(Client.class);
        when(client.getChannelServer()).thenReturn(channel);
        Character chr = Mockito.mock(Character.class);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    @Test
    void teleportWithCoordinatesPlacesExactPositionAndClearsTransitionFlag() {
        if (!enabled()) {
            return;
        }
        MapleMap target = Mockito.mock(MapleMap.class);
        when(target.getMapName()).thenReturn("Test Map");
        Character chr = characterResolving(target);
        AgentSession session = sessionOn(chr);

        Map<String, Object> out = TestOps.teleport(session, MAP_ID, null, -719, -287);

        verify(chr).changeMap(target, new Point(-719, -287));
        // Harness sessions never send PLAYER_MAP_TRANSFER; the op must clear the flag itself.
        verify(chr).setMapTransitionComplete();
        assertTrue(((String) out.get("message")).contains("103000802"));
    }

    @Test
    void teleportWithoutCoordinatesKeepsDefaultSpawn() {
        if (!enabled()) {
            return;
        }
        MapleMap target = Mockito.mock(MapleMap.class);
        when(target.getMapName()).thenReturn("Test Map");
        Character chr = characterResolving(target);
        AgentSession session = sessionOn(chr);

        TestOps.teleport(session, MAP_ID, null, null, null);

        verify(chr).changeMap(target);
        verify(chr, never()).changeMap(any(MapleMap.class), any(Point.class));
        verify(chr).setMapTransitionComplete();
    }

    @Test
    void teleportWithOnlyOneCoordinateIsRejected() {
        if (!enabled()) {
            return;
        }
        Character chr = characterResolving(Mockito.mock(MapleMap.class));
        AgentSession session = sessionOn(chr);

        assertThrows(IllegalArgumentException.class, () -> TestHarnessService.getInstance()
                .setup("teleport", session, Map.of("mapId", MAP_ID, "x", 10)));
    }

    @Test
    void killMobWithDropsRegistersDamageSourceForLootFilter() {
        if (!enabled()) {
            return;
        }
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        Monster mob = Mockito.mock(Monster.class);
        when(mob.getId()).thenReturn(9300002);
        when(map.getMonsterByOid(42)).thenReturn(mob);
        when(map.getId()).thenReturn(MAP_ID);
        when(mob.isAttackedBy(chr)).thenReturn(false);
        AgentSession session = sessionOn(chr);

        TestOps.killMob(session, 42, true);

        // The drop pipeline (USE_SPAWN_RELEVANT_LOOT) reads the mob's takenDamage set;
        // a harness kill must register the requester or no drops spawn.
        verify(mob).applyFakeDamage(chr, 1, true);
        verify(map).killMonster(mob, chr, true);
    }

    @Test
    void killMobWithoutDropsDoesNotTouchDamagePipeline() {
        if (!enabled()) {
            return;
        }
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        Monster mob = Mockito.mock(Monster.class);
        when(mob.getId()).thenReturn(9300002);
        when(map.getMonsterByOid(42)).thenReturn(mob);
        when(map.getId()).thenReturn(MAP_ID);
        AgentSession session = sessionOn(chr);

        TestOps.killMob(session, 42, false);

        verify(mob, never()).applyFakeDamage(any(), anyInt(), Mockito.anyBoolean());
        verify(map).killMonster(mob, chr, false);
    }

    @Test
    void killMobWithDropsSkipsAlreadyDamagedMob() {
        if (!enabled()) {
            return;
        }
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        Monster mob = Mockito.mock(Monster.class);
        when(mob.getId()).thenReturn(9300002);
        when(map.getMonsterByOid(42)).thenReturn(mob);
        when(map.getId()).thenReturn(MAP_ID);
        when(mob.isAttackedBy(chr)).thenReturn(true);
        AgentSession session = sessionOn(chr);

        TestOps.killMob(session, 42, true);

        verify(mob, never()).applyFakeDamage(any(), anyInt(), Mockito.anyBoolean());
        verify(map).killMonster(mob, chr, true);
    }

    @Test
    void killMobUnknownObjectIdIsRejected() {
        if (!enabled()) {
            return;
        }
        Character chr = Mockito.mock(Character.class);
        MapleMap map = Mockito.mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(map.getMonsterByOid(999)).thenReturn(null);
        when(map.getId()).thenReturn(MAP_ID);
        AgentSession session = sessionOn(chr);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestOps.killMob(session, 999, true));
        assertEquals("No monster with objectId 999 on map " + MAP_ID, e.getMessage());
    }

    @Test
    void setGenderAppliesToCharacterAndRejectsInvalidValues() {
        if (!enabled()) {
            return;
        }
        Character chr = Mockito.mock(Character.class);
        AgentSession session = sessionOn(chr);

        TestOps.setGender(session, 1);
        verify(chr).setGender(1);

        assertThrows(IllegalArgumentException.class, () -> TestOps.setGender(session, 2));
        verify(chr, never()).setGender(2);
    }
}
