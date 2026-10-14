package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.StepPlanner;

/** Builds the engine every autopilot uses. */
public final class Engines {
    private Engines() {}

    public static AutopilotEngine create() {
        return new SimpleEngine(new StepPlanner());
    }
}
