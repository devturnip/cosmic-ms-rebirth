package net.server.agent;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the player-facing pause: a paused session must not decide, must not run
 * server-driven behaviour and must preserve its queued events until it is resumed.
 */
class AgentSessionPauseTest {

    private ExecutorService brainPool;

    // saved config
    private boolean follow;
    private boolean sustain;
    private boolean combat;
    private boolean buff;
    private boolean heal;
    private boolean loot;
    private boolean recharge;
    private boolean flee;
    private boolean proactive;

    @BeforeEach
    void setUp() {
        brainPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-pause-test");
            t.setDaemon(true);
            return t;
        });
        var server = YamlConfig.config.server;
        follow = server.LLM_AGENT_FOLLOW;
        sustain = server.LLM_AGENT_AUTO_SUSTAIN;
        combat = server.LLM_AGENT_AUTO_COMBAT;
        buff = server.LLM_AGENT_AUTO_BUFF;
        heal = server.LLM_AGENT_AUTO_HEAL;
        loot = server.LLM_AGENT_AUTO_LOOT;
        recharge = server.LLM_AGENT_AUTO_RECHARGE;
        flee = server.LLM_AGENT_AUTO_FLEE;
        proactive = server.LLM_AGENT_PROACTIVE;
        // Disable all server-driven behaviour so a tick only exercises the decision path.
        server.LLM_AGENT_FOLLOW = false;
        server.LLM_AGENT_AUTO_SUSTAIN = false;
        server.LLM_AGENT_AUTO_COMBAT = false;
        server.LLM_AGENT_AUTO_BUFF = false;
        server.LLM_AGENT_AUTO_HEAL = false;
        server.LLM_AGENT_AUTO_LOOT = false;
        server.LLM_AGENT_AUTO_RECHARGE = false;
        server.LLM_AGENT_AUTO_FLEE = false;
        server.LLM_AGENT_PROACTIVE = false;
    }

    @AfterEach
    void tearDown() {
        var server = YamlConfig.config.server;
        server.LLM_AGENT_FOLLOW = follow;
        server.LLM_AGENT_AUTO_SUSTAIN = sustain;
        server.LLM_AGENT_AUTO_COMBAT = combat;
        server.LLM_AGENT_AUTO_BUFF = buff;
        server.LLM_AGENT_AUTO_HEAL = heal;
        server.LLM_AGENT_AUTO_LOOT = loot;
        server.LLM_AGENT_AUTO_RECHARGE = recharge;
        server.LLM_AGENT_AUTO_FLEE = flee;
        server.LLM_AGENT_PROACTIVE = proactive;
        brainPool.shutdownNow();
    }

    private static Character mockAgent() {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        return chr;
    }

    private static AgentSession newSession(AgentBrain brain) {
        return new AgentSession(new AgentClient(-1, null, 0, 0), mockAgent(), brain, new AgentMemory(1));
    }

    @Test
    void pausedTickDoesNotReachTheBrain() throws Exception {
        CountingBrain brain = new CountingBrain(List.of());
        AgentSession session = newSession(brain);
        session.setPaused(true);
        session.enqueue(new AgentEvent.Instruction("hello"));

        session.tick(brainPool);

        assertFalse(brain.decided.await(200, TimeUnit.MILLISECONDS), "a paused session must not decide");
        assertEquals(0, brain.calls.get());
        assertEquals(1, session.getQueuedEvents(), "queued events must survive the pause");
        session.shutdown();
    }

    @Test
    void pausedSessionDoesNotPerformBehaviour() {
        AgentSession session = newSession(new ScriptedAgentBrain("Test"));
        session.setPaused(true);

        assertFalse(session.perform(new AgentAction.Say("hello")), "a paused session must not perform behaviour");

        session.shutdown();
    }

    @Test
    void resumingLetsATickReachTheBrain() throws Exception {
        CountingBrain brain = new CountingBrain(List.of());
        AgentSession session = newSession(brain);
        session.setPaused(true);
        session.enqueue(new AgentEvent.Instruction("hello"));
        assertEquals(1, session.getQueuedEvents());

        session.setPaused(false);
        session.tick(brainPool);

        assertTrue(brain.decided.await(2, TimeUnit.SECONDS), "a resumed session must decide");
        assertEquals(1, brain.calls.get());
        assertEquals(0, session.getQueuedEvents(), "the preserved event is consumed on resume");
        session.shutdown();
    }

    @Test
    void pausingDropsAnInFlightDecision() throws Exception {
        CountDownLatch decided = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentBrain blockingBrain = new AgentBrain() {
            @Override
            public String describe() {
                return "blocking";
            }

            @Override
            public List<AgentAction> decide(AgentObservation observation, List<AgentEvent> events, AgentMemory memory) {
                decided.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new AgentAction.Say("hello"));
            }
        };
        AgentSession session = spy(newSession(blockingBrain));
        session.enqueue(new AgentEvent.Instruction("hello"));

        session.tick(brainPool);
        assertTrue(decided.await(2, TimeUnit.SECONDS), "the decision should be in flight");

        session.setPaused(true); // pause before the decision's actions are applied
        release.countDown();
        Thread.sleep(300); // give the action executor time to (not) apply the decision

        verify(session, never()).dispatchInbound(any(), any());
        session.shutdown();
    }

    @Test
    void setPausedIsIdempotent() {
        AgentSession session = newSession(new ScriptedAgentBrain("Test"));

        assertFalse(session.isPaused());
        session.setPaused(true);
        session.setPaused(true);
        assertTrue(session.isPaused());
        session.setPaused(false);
        session.setPaused(false);
        assertFalse(session.isPaused());

        session.shutdown();
    }

    /** Counts decisions and opens a latch so tests can await an async decision. */
    private static final class CountingBrain implements AgentBrain {
        private final List<AgentAction> actions;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch decided = new CountDownLatch(1);

        private CountingBrain(List<AgentAction> actions) {
            this.actions = actions;
        }

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public List<AgentAction> decide(AgentObservation observation, List<AgentEvent> events, AgentMemory memory) {
            calls.incrementAndGet();
            decided.countDown();
            return actions;
        }
    }
}
