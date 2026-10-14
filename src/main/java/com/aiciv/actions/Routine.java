package com.aiciv.actions;

/**
 * An action made of stages, each an ordinary action with its own timeout, chosen one at a time so a later stage can
 * depend on how the earlier ones went (find a fortress, then hunt blazes there). A failed stage fails the routine
 * unless {@link #retry} offers a replacement. Subclasses set the routine's own {@link #timeoutTicks()}.
 */
public abstract class Routine implements Action {
    private Action current;
    private int ticksOnCurrent;
    private int stagesDone;
    private boolean done;

    /** The next stage, or null when the routine is done. Called first and after each stage ends DONE. */
    protected abstract Action nextStage(BotContext ctx);

    /** A stage failed (or timed out); return a replacement to retry, or null to fail the routine. */
    protected Action retry(BotContext ctx, Action failed, String reason) {
        return null;
    }

    /** Shown by describe() once the routine is done; null keeps name(). */
    protected String summary() {
        return null;
    }

    protected abstract String name();

    @Override
    public final ActionStatus tick(BotContext ctx) {
        if (done) return ActionStatus.DONE;
        if (current == null && !start(nextStage(ctx))) return finish();
        ActionStatus status = current.tick(ctx);
        ticksOnCurrent++;
        if (status.state() == ActionStatus.State.RUNNING && ticksOnCurrent >= current.timeoutTicks()) {
            cancelQuietly(current, ctx);
            status = ActionStatus.failed("timed out");
        }
        switch (status.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case DONE:
                stagesDone++;
                current = null;
                return start(nextStage(ctx)) ? ActionStatus.RUNNING : finish();
            default: {
                Action failed = current;
                current = null;
                if (start(retry(ctx, failed, status.reason()))) return ActionStatus.RUNNING;
                return ActionStatus.failed(failed.describe() + ": " + status.reason());
            }
        }
    }

    private boolean start(Action stage) {
        current = stage;
        ticksOnCurrent = 0;
        return stage != null;
    }

    private ActionStatus finish() {
        done = true;
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (current != null) current.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (current != null) current.interrupted(ctx);
    }

    /** The current stage's answer; false between stages (a chosen stage that has not ticked yet has not begun). */
    @Override
    public boolean survivesDimensionChange() {
        return current != null && ticksOnCurrent > 0 && current.survivesDimensionChange();
    }

    @Override
    public String describe() {
        String s = done ? summary() : null;
        return s != null ? s : name();
    }

    protected final int stagesDone() {
        return stagesDone;
    }

    private static void cancelQuietly(Action action, BotContext ctx) {
        try {
            action.cancel(ctx);
        } catch (RuntimeException ignored) {
            // The stage is dropped either way.
        }
    }
}
