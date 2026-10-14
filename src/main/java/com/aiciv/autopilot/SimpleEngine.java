package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.GoalLibrary;
import com.aiciv.autopilot.goal.PlanResult;
import com.aiciv.autopilot.goal.StepPlanner;
import com.aiciv.autopilot.personality.Personality;

/** Works through the goal library in order, ignoring personality: the first goal with a next step wins. */
final class SimpleEngine implements AutopilotEngine {
    private final StepPlanner planner;

    SimpleEngine(StepPlanner planner) {
        this.planner = planner;
    }

    @Override
    public Decision decide(Situation s, AutopilotMemory mem, Personality p, String trigger, long tick) {
        Tuning t = tuning(p);
        for (Goal goal : GoalLibrary.all()) {
            if (planner.next(goal, s, mem, t) instanceof PlanResult.Next next) {
                return Decision.of(next, "goal " + goal.id() + ": step " + next.step().id() + ": " + next.move().why());
            }
        }
        return Decision.waiting("nothing to do");
    }
}
