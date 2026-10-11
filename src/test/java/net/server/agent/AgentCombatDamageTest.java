package net.server.agent;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class AgentCombatDamageTest {

    @Test
    void safeDamageUsesCalculateMaxBaseDamage() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getTotalWatk()).thenReturn(10);
        when(chr.calculateMaxBaseDamage(anyInt())).thenReturn(250);

        assertEquals(250, AgentCombat.safeDamage(chr));
    }

    @Test
    void safeDamageIsAtLeastOneWhenMaxBaseDamageIsZero() {
        Character chr = Mockito.mock(Character.class);
        when(chr.getTotalWatk()).thenReturn(0);
        when(chr.calculateMaxBaseDamage(anyInt())).thenReturn(0);

        assertEquals(1, AgentCombat.safeDamage(chr));
    }
}
