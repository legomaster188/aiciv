package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import java.util.Set;
import java.util.TreeSet;

/** Picks a goal's next step. Minimal version (no retries or backoff); Task 2 rewrites it. Pure. */
public class StepPlanner {
    public PlanResult next(Goal goal, Situation s, AutopilotMemory mem, Tuning tuning) {
        for (Step step : goal.steps()) {
            if (step.done(s)) continue;
            Set<String> missing = new TreeSet<>();
            for (String n : step.needs()) if (!s.hasAction(n)) missing.add(n);
            if (!missing.isEmpty()) {
                if (step.optional()) continue;
                return new PlanResult.Blocked(goal, step, "needs " + String.join(", ", missing) + " (not available yet)");
            }
            Move move = step.next(s);
            if (move.kind() == Move.Kind.BLOCKED) return new PlanResult.Blocked(goal, step, move.why());
            return new PlanResult.Next(goal, step, move);
        }
        return new PlanResult.Achieved(goal);
    }
}
