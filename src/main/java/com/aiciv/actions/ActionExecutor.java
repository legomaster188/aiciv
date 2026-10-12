package com.aiciv.actions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Runs one bot's queued actions in order, one at a time. Server thread only. */
public final class ActionExecutor {
    private final Deque<Action> queue = new ArrayDeque<>();
    private final List<String> finished = new ArrayList<>();
    private Action current;
    private int ticksOnCurrent;
    private boolean paused;

    public void replace(List<Action> actions, BotContext ctx) {
        stop(ctx);
        queue.addAll(actions);
    }

    public void stop(BotContext ctx) {
        if (current != null) current.cancel(ctx);
        current = null;
        queue.clear();
        finished.clear();
    }

    /** Holds the current action (and the queue) while a reflex has the bot. Tells the action it was interrupted. */
    public void pause(BotContext ctx) {
        if (paused) return;
        paused = true;
        if (current == null) return;
        try {
            current.interrupted(ctx);
        } catch (RuntimeException ignored) {
            // A broken interrupted() must not stop the reflex; the action re-plans from scratch or fails by itself.
        }
    }

    public void resume(BotContext ctx) {
        paused = false;
    }

    public boolean paused() {
        return paused;
    }

    public boolean busy() {
        return current != null || !queue.isEmpty();
    }

    /** Whether the action running now (or, between actions, the next one) is of this type. */
    public boolean upNext(Class<? extends Action> type) {
        return type.isInstance(current != null ? current : queue.peek());
    }

    public String currentDescription() {
        return current == null ? "none" : current.describe();
    }

    /** Runs one tick. Returns a summary when the whole sequence just ended, otherwise null. */
    public String tick(BotContext ctx) {
        if (paused) return null; // a reflex has the bot; timeouts do not run down meanwhile
        if (current == null) {
            current = queue.poll();
            ticksOnCurrent = 0;
            if (current == null) return null;
        }
        ActionStatus status = guardedTick(current, ctx);
        ticksOnCurrent++;
        if (status.state() == ActionStatus.State.RUNNING && ticksOnCurrent >= current.timeoutTicks()) {
            cancelQuietly(current, ctx);
            status = ActionStatus.failed("timed out");
        }
        switch (status.state()) {
            case RUNNING:
                return null;
            case DONE: {
                finished.add(current.describe());
                current = null;
                if (!queue.isEmpty()) return null;
                String summary = "finished: " + String.join(", ", finished);
                finished.clear();
                return summary;
            }
            default: {
                StringBuilder summary = new StringBuilder("failed: ").append(current.describe()).append(": ").append(status.reason());
                if (!queue.isEmpty()) {
                    List<String> skipped = new ArrayList<>();
                    for (Action a : queue) skipped.add(a.describe());
                    summary.append(" (skipped: ").append(String.join(", ", skipped)).append(')');
                }
                current = null;
                queue.clear();
                finished.clear();
                return summary.toString();
            }
        }
    }

    /** An action that throws fails instead of rethrowing into the server tick on every later tick. */
    private static ActionStatus guardedTick(Action action, BotContext ctx) {
        try {
            return action.tick(ctx);
        } catch (RuntimeException ex) {
            cancelQuietly(action, ctx);
            String message = ex.getMessage();
            return ActionStatus.failed(ex.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
    }

    private static void cancelQuietly(Action action, BotContext ctx) {
        try {
            action.cancel(ctx);
        } catch (RuntimeException ignored) {
            // The action is dropped either way, so a broken cancel must not break the tick.
        }
    }
}
