package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutopilotMemoryTest {
    @Test
    void failuresBackOffAfterMaxRetries() {
        AutopilotMemory m = new AutopilotMemory();
        m.fail("g", "s", 100, 3);
        m.fail("g", "s", 100, 3);
        assertFalse(m.coolingDown("g", "s", 100));
        m.fail("g", "s", 100, 3);
        assertEquals(3, m.failures("g", "s"));
        assertEquals(700, m.retryAt("g", "s"));
        assertTrue(m.coolingDown("g", "s", 699));
        assertFalse(m.coolingDown("g", "s", 700));
        m.fail("g", "s", 1000, 3);
        assertEquals(2200, m.retryAt("g", "s"));
        assertEquals(600, AutopilotMemory.backoffTicks(1));
        assertEquals(1200, AutopilotMemory.backoffTicks(2));
        assertEquals(4800, AutopilotMemory.backoffTicks(4));
        assertEquals(6000, AutopilotMemory.backoffTicks(5));
        assertEquals(6000, AutopilotMemory.backoffTicks(40));
    }

    @Test
    void outcomeOfAFailedStepCountsAFailure() {
        AutopilotMemory m = new AutopilotMemory();
        m.stepStarted("g", "s", null, 10);
        m.outcome("failed: mine oak_log: nothing to mine", 20, 3);
        assertEquals(1, m.failures("g", "s"));
        assertTrue(m.inFlight().isEmpty());
    }

    @Test
    void finishedClearsFailuresAndSetsFlagAndLastDone() {
        AutopilotMemory m = new AutopilotMemory();
        for (int i = 0; i < 3; i++) m.fail("farm", "plant", 0, 3);
        m.stepStarted("farm", "plant", "planted", 5);
        assertEquals("farm", m.goal());
        assertEquals("plant", m.step());
        assertEquals("plant", m.inFlight().orElseThrow().step());
        m.outcome("finished: plant wheat_seeds", 50, 3);
        assertEquals(0, m.failures("farm", "plant"));
        assertFalse(m.coolingDown("farm", "plant", 51));
        assertTrue(m.flags().contains("planted"));
        assertEquals(50L, m.lastDone().get("farm/plant"));
        assertTrue(m.inFlight().isEmpty());
    }

    @Test
    void finishesWithoutDoneCountPerStepAndResetOnAnotherStep() {
        AutopilotMemory m = new AutopilotMemory();
        for (int i = 0; i < 2; i++) {
            m.stepStarted("g", "a", null, i);
            m.outcome("finished: x", i, 3);
        }
        assertEquals(2, m.finishesWithoutDone("g", "a"));
        m.stepStarted("g", "a", null, 5);
        assertEquals(2, m.finishesWithoutDone("g", "a"), "restarting the same step keeps the count");
        m.stepStarted("g", "b", null, 6);
        assertEquals(0, m.finishesWithoutDone("g", "a"));
        m.outcome("finished: y", 7, 3);
        assertEquals(1, m.finishesWithoutDone("g", "b"));
        m.resetFinishes("g", "b");
        assertEquals(0, m.finishesWithoutDone("g", "b"));
    }

    @Test
    void blockedGoalsExpire() {
        AutopilotMemory m = new AutopilotMemory();
        m.block("beat_game", 1300);
        assertTrue(m.blocked("beat_game", 1299));
        assertFalse(m.blocked("beat_game", 1300));
        assertFalse(m.blocked("farm", 0));
    }

    @Test
    void requestsAreOnePerSpeakerAtMostFourAndExpire() {
        AutopilotMemory m = new AutopilotMemory();
        m.heard(new AutopilotMemory.Request("GIVE", "Bob", "bread", 3, 100));
        m.heard(new AutopilotMemory.Request("COME", "Bob", null, 1, 110));
        assertEquals(List.of("COME"), m.requests(110).stream().map(AutopilotMemory.Request::kind).toList());
        for (String who : List.of("A", "B", "C", "D")) m.heard(new AutopilotMemory.Request("GIVE", who, "apple", 1, 120));
        List<AutopilotMemory.Request> r = m.requests(120);
        assertEquals(4, r.size());
        assertEquals(List.of("A", "B", "C", "D"), r.stream().map(AutopilotMemory.Request::from).toList());
        m.handled(r.get(0));
        assertEquals(3, m.requests(120).size());
        assertEquals(3, m.requests(2520).size());
        assertEquals(0, m.requests(2521).size());
    }

    @Test
    void aRequestIsHandledOnlyWhenItsStepFinishesAndRetriedOnceOnFailure() {
        AutopilotMemory m = new AutopilotMemory();
        AutopilotMemory.Request bread = new AutopilotMemory.Request("GIVE", "Bob", "bread", 3, 100, true);
        m.heard(bread);
        m.stepStarted("social", "request_give", null, 100);
        m.answering(bread);
        m.outcome("failed: Bob is too far", 110, 3);
        assertEquals(List.of(bread), m.requests(110), "one failure: try again");
        m.stepStarted("social", "request_give", null, 120);
        m.answering(bread);
        m.outcome("failed: Bob is too far", 130, 3);
        assertTrue(m.requests(130).isEmpty(), "two failures: dropped");

        AutopilotMemory.Request apple = new AutopilotMemory.Request("GIVE", "Cy", "apple", 1, 200, true);
        m.heard(apple);
        m.stepStarted("social", "request_give", null, 200);
        m.answering(apple);
        m.stepStarted("get_food", "bread", null, 205); // replaced before it finished: the request still waits
        m.outcome("finished: craft bread", 210, 3);
        assertEquals(List.of(apple), m.requests(210));
        m.stepStarted("social", "request_give", null, 220);
        m.answering(apple);
        m.outcome("finished: give apple", 230, 3);
        assertTrue(m.requests(230).isEmpty());
    }

    @Test
    void aRequestIsAcknowledgedOnce() {
        AutopilotMemory m = new AutopilotMemory();
        AutopilotMemory.Request r = new AutopilotMemory.Request("COME", "Bob", "", 1, 100, true);
        assertFalse(m.acknowledge(r), "not pending");
        m.heard(r);
        assertTrue(m.acknowledge(r));
        assertFalse(m.acknowledge(r));
    }
}
