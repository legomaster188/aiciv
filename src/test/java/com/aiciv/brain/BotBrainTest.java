package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BotBrainTest {
    static final long IDLE = 60_000, MENTION = 15_000;
    long t0 = 1_000_000;

    BotBrain brain() {
        return new BotBrain("Ada", "cheerful", "", "gemini-flash", 3, IDLE, MENTION, t0);
    }

    @Test
    void thinksRightAfterJoiningThenWaitsForIdle() {
        BotBrain b = brain();
        assertEquals(WakeReason.IDLE, b.pollWake(t0, false));
        b.dispatched(t0);
        b.replied("say hi");
        assertNull(b.pollWake(t0 + 1000, false));
        assertNull(b.pollWake(t0 + IDLE - 1, false));
        assertEquals(WakeReason.IDLE, b.pollWake(t0 + IDLE, false));
    }

    @Test
    void busyBotsDoNotIdleWake() {
        BotBrain b = brain();
        assertNull(b.pollWake(t0, true));
    }

    @Test
    void awaitingReplyBlocksWakesButKeepsThem() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.actionsEnded("s", "finished: say hi");
        assertNull(b.pollWake(t0 + 10, false));
        b.replied("x");
        assertEquals(WakeReason.ACTION_ENDED, b.pollWake(t0 + 20, false));
    }

    @Test
    void mentionWakesOncePerCooldown() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("s", "Steve", "hi Ada", true, t0 + 100);
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("say hi");
        b.hearChat("s", "Steve", "Ada?", true, t0 + 2000);
        assertNull(b.pollWake(t0 + 2000, true));
    }

    @Test
    void mentionDuringCooldownWakesWhenItEnds() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("s", "Steve", "Ada come here", true, t0 + 100);
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("say ok");
        b.hearChat("s", "Steve", "Ada, faster", true, t0 + 3000);
        assertNull(b.pollWake(t0 + 3000, true));
        assertNull(b.pollWake(t0 + 100 + MENTION - 1, true));
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 100 + MENTION, true));
        assertEquals(2, b.events().lines().size());
    }

    @Test
    void chatWithoutMentionOnlyLogs() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hearChat("day 1, 06:00", "Steve", "nice weather", false, t0 + 100);
        assertNull(b.pollWake(t0 + 100, true));
        assertEquals("[day 1, 06:00] Steve said: \"nice weather\"", b.events().lines().get(0));
    }

    @Test
    void hurtWakesAreRateLimited() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.replied("x");
        b.hurt("s", "burning", t0 + 100);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 100, true));
        b.dispatched(t0 + 100);
        b.replied("goTo away");
        b.hurt("s", "burning", t0 + 1100);
        b.hurt("s", "burning", t0 + 2100);
        assertNull(b.pollWake(t0 + 2100, true));
        assertEquals(1, b.events().lines().size(), "suppressed hurts must not add events");
        b.hurt("s", "burning", t0 + 100 + BotBrain.HURT_COOLDOWN_MILLIS);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 100 + BotBrain.HURT_COOLDOWN_MILLIS, true));
    }

    @Test
    void mostUrgentPendingWins() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.actionsEnded("s", "done");
        b.hurt("s", "ouch", t0);
        b.replied("x");
        assertEquals(WakeReason.HURT, b.pollWake(t0 + 1, false));
        assertNull(b.pollWake(t0 + 2, false));
    }

    @Test
    void failedRequestRaisesItsReasonAgain() {
        BotBrain b = brain();
        b.dispatched(t0);
        b.failed(WakeReason.MENTIONED);
        assertFalse(b.awaitingReply());
        assertEquals(WakeReason.MENTIONED, b.pollWake(t0 + 1, true));
    }

    @Test
    void pausedBotsNeverWake() {
        BotBrain b = brain();
        b.setPaused(true);
        b.hurt("s", "ouch", t0);
        assertNull(b.pollWake(t0 + IDLE * 2, false));
        b.setPaused(false);
        assertEquals(WakeReason.HURT, b.pollWake(t0 + IDLE * 2, false));
    }

    @Test
    void eventLogKeepsTheNewest() {
        BotBrain b = brain();
        for (int i = 0; i < 5; i++) b.note("s", "event " + i);
        assertEquals(java.util.List.of("[s] event 2", "[s] event 3", "[s] event 4"), b.events().lines());
    }
}
