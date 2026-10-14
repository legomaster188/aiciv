package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks a goal's next step. A step whose action is missing is skipped (optional) or blocks the goal; a step that keeps
 * failing, or keeps finishing without becoming done, cools down; a gather step that keeps failing explores instead. Pure.
 */
public class StepPlanner {
    private static final List<String> DIRECTIONS = List.of("N", "E", "S", "W");

    public PlanResult next(Goal goal, Situation s, AutopilotMemory mem, Tuning t) {
        for (Step step : goal.steps()) {
            if (step.done(s)) continue;
            Set<String> missing = new TreeSet<>();
            for (String n : step.needs()) if (!s.hasAction(n)) missing.add(n);
            if (!missing.isEmpty()) {
                if (step.optional()) continue;
                return new PlanResult.Blocked(goal, step, "needs " + String.join(", ", missing) + " (not available yet)");
            }
            if (mem.finishesWithoutDone(goal.id(), step.id()) >= 3) {
                mem.resetFinishes(goal.id(), step.id());
                mem.stall(goal.id(), step.id(), s.tick(), t.maxRetries());
            }
            if (mem.coolingDown(goal.id(), step.id(), s.tick())) {
                if (step.optional()) continue;
                long secs = (mem.retryAt(goal.id(), step.id()) - s.tick() + 19) / 20;
                return new PlanResult.Blocked(goal, step,
                        "step " + step.id() + " failed " + mem.failures(goal.id(), step.id()) + " times; retry in " + secs + " s");
            }
            Move move = step.next(s);
            if (move.kind() == Move.Kind.BLOCKED) {
                if (step.optional()) continue;
                return new PlanResult.Blocked(goal, step, move.why());
            }
            int failures = mem.failures(goal.id(), step.id());
            if (move.kind() == Move.Kind.GATHER && failures >= 2 && failures % 2 == 0 && s.hasAction("explore")) {
                String dir = DIRECTIONS.get(Math.floorMod(goal.id().hashCode() + failures / 2, 4));
                move = Move.gather(move.why() + " (not found nearby; exploring " + dir + ")",
                        Calls.of("explore", "direction", dir, "distance", t.exploreDistance()));
            }
            return new PlanResult.Next(goal, step, move);
        }
        return new PlanResult.Achieved(goal);
    }
}
