package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.FakeContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionExecutorTest {
    static final class Scripted implements Action {
        final String name;
        final Deque<ActionStatus> statuses;
        final int timeout;
        int cancels;

        Scripted(String name, int timeout, ActionStatus... statuses) {
            this.name = name;
            this.timeout = timeout;
            this.statuses = new ArrayDeque<>(List.of(statuses));
        }

        @Override public ActionStatus tick(BotContext ctx) { ticked++; return statuses.isEmpty() ? ActionStatus.RUNNING : statuses.poll(); }
        @Override public String describe() { return name; }
        @Override public int timeoutTicks() { return timeout; }
        @Override public void cancel(BotContext ctx) { cancels++; }
        int ticked;
        int interrupts;
        @Override public void interrupted(BotContext ctx) { interrupts++; }
    }

    FakeContext ctx = new FakeContext();
    ActionExecutor ex = new ActionExecutor();

    @Test
    void sequenceRunsInOrderAndReportsOnce() {
        ex.replace(List.of(new Scripted("a", 100, ActionStatus.DONE), new Scripted("b", 100, ActionStatus.RUNNING, ActionStatus.DONE)), ctx);
        assertTrue(ex.busy());
        assertNull(ex.tick(ctx));
        assertNull(ex.tick(ctx));
        assertEquals("b", ex.currentDescription());
        assertEquals("finished: a, b", ex.tick(ctx));
        assertFalse(ex.busy());
        assertNull(ex.tick(ctx));
    }

    @Test
    void failureSkipsTheRest() {
        ex.replace(List.of(new Scripted("a", 100, ActionStatus.failed("no path")), new Scripted("b", 100, ActionStatus.DONE)), ctx);
        assertEquals("failed: a: no path (skipped: b)", ex.tick(ctx));
        assertFalse(ex.busy());
    }

    @Test
    void timeoutCancelsAndFails() {
        Scripted slow = new Scripted("slow", 3);
        ex.replace(List.of(slow), ctx);
        assertNull(ex.tick(ctx));
        assertNull(ex.tick(ctx));
        assertEquals("failed: slow: timed out", ex.tick(ctx));
        assertEquals(1, slow.cancels);
    }

    @Test
    void replaceCancelsTheCurrentAction() {
        Scripted first = new Scripted("first", 100);
        ex.replace(List.of(first), ctx);
        ex.tick(ctx);
        ex.replace(List.of(new Scripted("second", 100, ActionStatus.DONE)), ctx);
        assertEquals(1, first.cancels);
        assertEquals("finished: second", ex.tick(ctx));
    }

    /** An action whose tick throws, and optionally whose cancel throws too. */
    static final class Throwing implements Action {
        final boolean cancelThrows;
        int cancels;

        Throwing(boolean cancelThrows) {
            this.cancelThrows = cancelThrows;
        }

        @Override public ActionStatus tick(BotContext ctx) { throw new IllegalStateException("kaboom"); }
        @Override public String describe() { return "boom"; }
        @Override public int timeoutTicks() { return 100; }
        @Override public void cancel(BotContext ctx) {
            cancels++;
            if (cancelThrows) throw new IllegalStateException("cancel broke");
        }
    }

    @Test
    void throwingActionFailsAndSkipsTheRest() {
        Throwing boom = new Throwing(false);
        ex.replace(List.of(boom, new Scripted("b", 100, ActionStatus.DONE)), ctx);
        assertEquals("failed: boom: IllegalStateException: kaboom (skipped: b)", ex.tick(ctx));
        assertFalse(ex.busy());
        assertNull(ex.tick(ctx));
        assertEquals(1, boom.cancels);
    }

    @Test
    void throwingCancelDoesNotEscapeTheTick() {
        Throwing boom = new Throwing(true);
        ex.replace(List.of(boom), ctx);
        assertEquals("failed: boom: IllegalStateException: kaboom", ex.tick(ctx));
        assertFalse(ex.busy());
        assertEquals(1, boom.cancels);
    }

    @Test
    void stopClearsEverything() {
        Scripted a = new Scripted("a", 100);
        ex.replace(List.of(a, new Scripted("b", 100)), ctx);
        ex.tick(ctx);
        ex.stop(ctx);
        assertFalse(ex.busy());
        assertEquals("none", ex.currentDescription());
        assertEquals(1, a.cancels);
    }

    @Test
    void pauseInterruptsOnceAndFreezesTheAction() {
        Scripted a = new Scripted("a", 3);
        ex.replace(List.of(a), ctx);
        assertNull(ex.tick(ctx));
        ex.pause(ctx);
        ex.pause(ctx);
        assertTrue(ex.paused());
        assertEquals(1, a.interrupts);
        for (int i = 0; i < 10; i++) assertNull(ex.tick(ctx)); // would have timed out if it ran down
        assertEquals(1, a.ticked);
        ex.resume(ctx);
        assertFalse(ex.paused());
        assertNull(ex.tick(ctx));
        assertEquals(2, a.ticked);
        assertEquals("a", ex.currentDescription());
    }

    @Test
    void pausedExecutorHoldsQueuedActionsToo() {
        Scripted a = new Scripted("a", 100, ActionStatus.DONE);
        ex.replace(List.of(a), ctx);
        ex.pause(ctx); // nothing is current yet, so nothing to interrupt
        assertEquals(0, a.interrupts);
        assertNull(ex.tick(ctx));
        assertEquals(0, a.ticked);
        assertTrue(ex.busy());
        ex.resume(ctx);
        assertEquals("finished: a", ex.tick(ctx));
    }
}
