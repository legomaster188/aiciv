package com.aiciv.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Shares a fixed amount of search time per server tick between all running searches. */
public final class NavBudget {
    public static final long DEFAULT_NANOS = 2_000_000L;
    static final int STEP = 200;

    private final long budgetNanos;
    private final LongSupplier nanoClock;
    private final List<PathSearch> active = new ArrayList<>();
    private int cursor;

    public NavBudget(long budgetNanos, LongSupplier nanoClock) {
        this.budgetNanos = budgetNanos;
        this.nanoClock = nanoClock;
    }

    public void submit(PathSearch search) {
        active.add(search);
    }

    public void tick() {
        long deadline = nanoClock.getAsLong() + budgetNanos;
        while (!active.isEmpty() && nanoClock.getAsLong() < deadline) {
            if (cursor >= active.size()) cursor = 0;
            PathSearch s = active.get(cursor);
            if (s.state() != PathSearch.State.RUNNING || s.advance(STEP) != PathSearch.State.RUNNING) {
                active.remove(cursor);
            } else {
                cursor++;
            }
        }
        active.removeIf(s -> s.state() != PathSearch.State.RUNNING);
    }

    public int activeCount() {
        return active.size();
    }
}
