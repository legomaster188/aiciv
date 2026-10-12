package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThinkQueueTest {
    @Test
    void priorityThenOldestFirst() {
        ThinkQueue q = new ThinkQueue();
        q.offer("idle1", WakeReason.IDLE, 1);
        q.offer("ended", WakeReason.ACTION_ENDED, 5);
        q.offer("hurt", WakeReason.HURT, 9);
        q.offer("idle0", WakeReason.IDLE, 0);
        assertEquals("hurt", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("ended", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("idle0", q.pollFirst(b -> true).orElseThrow().bot());
        assertEquals("idle1", q.pollFirst(b -> true).orElseThrow().bot());
        assertTrue(q.pollFirst(b -> true).isEmpty());
    }

    @Test
    void reofferKeepsHigherPriorityAndEarliestTime() {
        ThinkQueue q = new ThinkQueue();
        q.offer("a", WakeReason.IDLE, 10);
        q.offer("a", WakeReason.MENTIONED, 20);
        q.offer("a", WakeReason.IDLE, 5);
        ThinkQueue.Ticket t = q.pollFirst(b -> true).orElseThrow();
        assertEquals(WakeReason.MENTIONED, t.reason());
        assertEquals(5, t.since());
        assertEquals(0, q.size());
    }

    @Test
    void blockedBotsAreSkippedNotDropped() {
        ThinkQueue q = new ThinkQueue();
        q.offer("blocked", WakeReason.HURT, 0);
        q.offer("free", WakeReason.IDLE, 0);
        assertEquals("free", q.pollFirst(b -> !b.equals("blocked")).orElseThrow().bot());
        assertEquals(1, q.positionOf("blocked"));
        assertEquals(-1, q.positionOf("free"));
        q.remove("blocked");
        assertEquals(0, q.size());
    }
}
