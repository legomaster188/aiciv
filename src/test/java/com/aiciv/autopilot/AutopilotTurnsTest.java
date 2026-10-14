package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutopilotTurnsTest {
    private static List<String> bots(List<AutopilotTurns.Turn> turns) {
        return turns.stream().map(AutopilotTurns.Turn::bot).toList();
    }

    @Test
    void roundRobinInOfferOrder() {
        AutopilotTurns t = new AutopilotTurns();
        for (int i = 0; i < 10; i++) t.offer("b" + i, "IDLE", null);
        assertEquals(10, t.size());
        assertEquals(List.of("b0", "b1", "b2", "b3"), bots(t.take(4)));
        assertEquals(List.of("b4", "b5", "b6", "b7"), bots(t.take(4)));
        assertEquals(List.of("b8", "b9"), bots(t.take(4)));
        assertEquals(0, t.size());
        assertTrue(t.take(4).isEmpty());
    }

    @Test
    void aWaitingBotKeepsItsPlace() {
        AutopilotTurns t = new AutopilotTurns();
        t.offer("a", "IDLE", null);
        t.offer("b", "IDLE", null);
        t.offer("a", "IDLE", null);
        assertEquals(List.of("a", "b"), bots(t.take(4)));
    }

    @Test
    void theMoreUrgentTriggerWins() {
        AutopilotTurns t = new AutopilotTurns();
        t.offer("a", "IDLE", null);
        t.offer("b", "ACTION_ENDED", null);
        t.offer("a", "URGENT", "night fell");
        t.offer("b", "IDLE", null);
        t.offer("c", "MENTIONED", null);
        t.offer("c", "HURT", null);
        List<AutopilotTurns.Turn> taken = t.take(4);
        assertEquals(new AutopilotTurns.Turn("a", "URGENT", "night fell"), taken.get(0));
        assertEquals("ACTION_ENDED", taken.get(1).trigger());
        assertEquals("HURT", taken.get(2).trigger());
    }

    @Test
    void removeDropsAWaitingBot() {
        AutopilotTurns t = new AutopilotTurns();
        t.offer("a", "IDLE", null);
        t.offer("b", "IDLE", null);
        t.remove("a");
        assertEquals(List.of("b"), bots(t.take(4)));
    }
}
