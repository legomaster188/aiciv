package com.aiciv.nav;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.GridView;
import org.junit.jupiter.api.Test;

class NavBudgetTest {
    long fakeNanos;

    /** Unreachable goal (y = 50), so the search keeps running until its node cap. */
    PathSearch longSearch() {
        GridView g = new GridView().floor(-200, 200, 0, -200, 200);
        return new PathSearch(g, new Pos(0, 1, 0), new NearGoal(150.5, 50, 150.5, 0.5), PathSearch.MAX_NODES);
    }

    @Test
    void sharesTheBudgetRoundRobin() {
        NavBudget budget = new NavBudget(10_000, () -> fakeNanos += 1_000);
        PathSearch a = longSearch(), b = longSearch();
        budget.submit(a);
        budget.submit(b);
        budget.tick();
        assertTrue(a.expandedCount() > 0);
        assertTrue(b.expandedCount() > 0);
        assertEquals(2, budget.activeCount());
    }

    @Test
    void finishedAndCancelledSearchesLeave() {
        NavBudget budget = new NavBudget(1_000_000_000L, System::nanoTime);
        PathSearch quick = new PathSearch(new GridView().floor(0, 3, 0, 0, 0), new Pos(0, 1, 0), new NearGoal(3.5, 1, 0.5, 0.5), PathSearch.MAX_NODES);
        PathSearch cancelled = longSearch();
        cancelled.cancel();
        budget.submit(quick);
        budget.submit(cancelled);
        budget.tick();
        assertEquals(PathSearch.State.FOUND, quick.state());
        assertEquals(0, budget.activeCount());
    }
}
