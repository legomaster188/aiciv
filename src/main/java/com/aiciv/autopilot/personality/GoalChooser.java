package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.autopilot.goal.Goal;
import java.util.ArrayList;
import java.util.List;

/** Ranks goals by personality weight times need. Pure. */
public final class GoalChooser {
    /** Goals below this utility are not worth starting. */
    static final double MIN_UTILITY = 0.05;
    /** A person's request outranks every goal (a pinned one too); only retreat, hunger, the night and danger come first. */
    static final double HUMAN_REQUEST_UTILITY = 3.0;
    private static final int DANGER_RANGE = 8;

    public record Scored(Goal goal, double utility) {}

    private GoalChooser() {}

    /** Usable goals, best first; equal utilities keep the order of {@code goals}. */
    public static List<Scored> rank(List<Goal> goals, Personality p, Situation s, AutopilotMemory mem, Tuning t, long tick) {
        List<Scored> out = new ArrayList<>();
        for (Goal g : goals) {
            String id = g.id();
            if (mem.blocked(id, tick)) continue;
            double w = p.goals().getOrDefault(id, 0.0);
            if (id.equals("social") && !t.helpsWhenAsked()) w = 0;
            if (id.equals("store_items") && t.depositWhenFree() == 0) w = 0;
            double need = g.need(s);
            double u = w * need;
            if (id.equals(mem.goal()) && need > 0) u += t.hysteresis();
            if (id.equals(s.pinnedGoal())) u += 1.0;
            if (id.equals("social") && t.helpsWhenAsked() && need > 0 && humanAsked(s) && s.nearestHostile(DANGER_RANGE).isEmpty()) {
                u = Math.max(u, HUMAN_REQUEST_UTILITY);
            }
            if (u >= MIN_UTILITY) out.add(new Scored(g, u));
        }
        out.sort((a, b) -> Double.compare(b.utility(), a.utility())); // stable: ties keep the library order
        return out;
    }

    private static boolean humanAsked(Situation s) {
        for (AutopilotMemory.Request r : s.requests()) if (r.human()) return true;
        return false;
    }
}
