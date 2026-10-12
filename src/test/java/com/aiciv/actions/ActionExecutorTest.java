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

        @Override public ActionStatus tick(BotContext ctx) { return statuses.isEmpty() ? ActionStatus.RUNNING : statuses.poll(); }
        @Override public String describe() { return name; }
        @Override public int timeoutTicks() { return timeout; }
        @Override public void cancel(BotContext ctx) { cancels++; }
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
}
