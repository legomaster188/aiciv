package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.FakeContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutineTest {
    /** A stage that answers a scripted status per tick, then RUNNING forever. */
    static final class Stage implements Action {
        final String name;
        final Deque<ActionStatus> statuses;
        int timeout = 100;
        boolean survives;
        int ticks, cancels, interrupts;

        Stage(String name, ActionStatus... statuses) {
            this.name = name;
            this.statuses = new ArrayDeque<>(List.of(statuses));
        }

        @Override public ActionStatus tick(BotContext ctx) { ticks++; return statuses.isEmpty() ? ActionStatus.RUNNING : statuses.poll(); }
        @Override public String describe() { return name; }
        @Override public int timeoutTicks() { return timeout; }
        @Override public void cancel(BotContext ctx) { cancels++; }
        @Override public void interrupted(BotContext ctx) { interrupts++; }
        @Override public boolean survivesDimensionChange() { return survives; }
    }

    /** Runs the given stages in order; {@code retries} replace a failed stage, one each. */
    static final class Script extends Routine {
        final Deque<Action> stages;
        final Deque<Action> retries = new ArrayDeque<>();
        final List<String> retried = new ArrayList<>();

        Script(Action... stages) {
            this.stages = new ArrayDeque<>(List.of(stages));
        }

        @Override protected Action nextStage(BotContext ctx) { return stages.poll(); }
        @Override protected Action retry(BotContext ctx, Action failed, String reason) {
            retried.add(failed.describe() + ": " + reason);
            return retries.poll();
        }
        @Override protected String summary() { return "did " + stagesDone() + " stages"; }
        @Override protected String name() { return "script"; }
        @Override public int timeoutTicks() { return 1000; }
    }

    private final FakeContext ctx = new FakeContext();

    private static ActionStatus runUntilDone(Routine r, BotContext ctx, int max) {
        for (int i = 0; i < max; i++) {
            ActionStatus s = r.tick(ctx);
            if (s.state() != ActionStatus.State.RUNNING) return s;
        }
        return ActionStatus.RUNNING;
    }

    @Test
    void twoDoneStagesFinishTheRoutine() {
        Stage a = new Stage("stage-a", ActionStatus.RUNNING, ActionStatus.DONE), b = new Stage("stage-b", ActionStatus.DONE);
        Script r = new Script(a, b);
        assertEquals("script", r.describe());
        assertEquals(ActionStatus.State.DONE, runUntilDone(r, ctx, 10).state());
        assertEquals(2, r.stagesDone());
        assertEquals("did 2 stages", r.describe());
        assertEquals(2, a.ticks);
        assertEquals(1, b.ticks);
    }

    @Test
    void aFailingStageFailsTheRoutine() {
        Script r = new Script(new Stage("stage-a", ActionStatus.DONE), new Stage("stage-b", ActionStatus.failed("broke")), new Stage("stage-c"));
        assertEquals(ActionStatus.failed("stage-b: broke"), runUntilDone(r, ctx, 10));
        assertEquals(List.of("stage-b: broke"), r.retried);
        assertEquals("script", r.describe());
    }

    @Test
    void aRetryReplacesTheFailedStage() {
        Stage again = new Stage("stage-b2", ActionStatus.DONE);
        Script r = new Script(new Stage("stage-b", ActionStatus.failed("broke")));
        r.retries.add(again);
        assertEquals(ActionStatus.State.DONE, runUntilDone(r, ctx, 10).state());
        assertEquals(1, again.ticks);
        assertEquals(1, r.stagesDone());
    }

    @Test
    void aStageOverItsOwnTimeoutIsCancelledAndFails() {
        Stage slow = new Stage("stage-c");
        slow.timeout = 5;
        Script r = new Script(slow);
        for (int i = 0; i < 4; i++) assertEquals(ActionStatus.RUNNING, r.tick(ctx));
        assertEquals(ActionStatus.failed("stage-c: timed out"), r.tick(ctx));
        assertEquals(1, slow.cancels);
    }

    @Test
    void cancelAndInterruptReachTheRunningStage() {
        Stage running = new Stage("stage-a");
        Script r = new Script(running);
        r.tick(ctx);
        r.interrupted(ctx);
        r.cancel(ctx);
        assertEquals(1, running.interrupts);
        assertEquals(1, running.cancels);
    }

    @Test
    void survivingADimensionChangeFollowsTheCurrentStage() {
        Stage walk = new Stage("walk", ActionStatus.DONE), portal = new Stage("portal");
        portal.survives = true;
        Script r = new Script(walk, portal);
        assertFalse(r.survivesDimensionChange(), "before the first stage");
        r.tick(ctx);
        assertFalse(r.survivesDimensionChange(), "between stages");
        r.tick(ctx);
        assertTrue(r.survivesDimensionChange());
    }
}
