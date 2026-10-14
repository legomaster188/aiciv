package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import org.junit.jupiter.api.Test;

class PlannerSpeedTest {
    @Test
    void tenThousandPlansTakeUnderASecond() {
        Situation mid = sit().inv("stone_pickaxe", 1).inv("wooden_pickaxe", 1).inv("oak_log", 5).inv("cobblestone", 20).inv("coal", 3)
                .inv("raw_iron", 4).inv("bread", 3).wear("iron_helmet").block("furnace", 6).block("crafting_table", 3)
                .mob("cow", 3, 12, "N").build();
        StepPlanner planner = new StepPlanner();
        AutopilotMemory mem = new AutopilotMemory();
        planner.next(CoreGoals.list().get(0), mid, mem, Tuning.DEFAULT); // warm up
        long start = System.nanoTime();
        int plans = 0;
        while (plans < 10_000) {
            for (Goal g : CoreGoals.list()) {
                planner.next(g, mid, mem, Tuning.DEFAULT);
                plans++;
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 1000, plans + " plans took " + ms + " ms");
    }
}
