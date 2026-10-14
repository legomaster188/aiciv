package com.aiciv.autopilot.goal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every goal: the core goals, then the world goals. Never edited after Task 1; the two lists fill it. */
public final class GoalLibrary {
    private GoalLibrary() {}

    public static List<Goal> all() {
        List<Goal> out = new ArrayList<>(CoreGoals.list());
        out.addAll(WorldGoals.list());
        return out;
    }

    public static Optional<Goal> byId(String id) {
        return all().stream().filter(g -> g.id().equals(id)).findFirst();
    }
}
