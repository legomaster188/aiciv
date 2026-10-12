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

    public boolean busy() {
        return current != null || !queue.isEmpty();
    }

    public String currentDescription() {
        return current == null ? "none" : current.describe();
    }

    /** Runs one tick. Returns a summary when the whole sequence just ended, otherwise null. */
    public String tick(BotContext ctx) {
        if (current == null) {
            current = queue.poll();
            ticksOnCurrent = 0;
            if (current == null) return null;
        }
        ActionStatus status = current.tick(ctx);
        ticksOnCurrent++;
        if (status.state() == ActionStatus.State.RUNNING && ticksOnCurrent >= current.timeoutTicks()) {
            current.cancel(ctx);
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
}
