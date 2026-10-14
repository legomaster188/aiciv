package com.aiciv.autopilot.goal.world;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.goal.Goal;
import com.aiciv.autopilot.goal.Move;
import com.aiciv.autopilot.goal.Step;
import com.aiciv.autopilot.goal.Steps;
import java.util.List;
import java.util.Set;

/** Wander one way for five minutes, then another; never finished. */
public final class Explore implements Goal {
    private static final List<String> DIRS8 = List.of("N", "NE", "E", "SE", "S", "SW", "W", "NW");

    private static final Step WANDER = Steps.of("wander", s -> false, s -> {
        String dir = DIRS8.get((int) ((Math.abs((long) s.name().hashCode()) + s.tick() / 6000) % 8));
        return Move.gather("explore " + dir, Calls.of("explore", "direction", dir, "distance", s.tuning().exploreDistance()));
    }, Set.of("explore"), false, null);

    private static final List<Step> STEPS = List.of(WANDER);

    @Override public String id() { return "explore"; }
    @Override public String summary() { return "explore"; }
    @Override public List<Step> steps() { return STEPS; }
    @Override public double need(Situation s) { return 0.6; }
}
