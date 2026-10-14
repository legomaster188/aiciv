package com.aiciv.autopilot;

import com.aiciv.autopilot.goal.GoalLibrary;
import com.aiciv.autopilot.goal.StepPlanner;
import com.aiciv.autopilot.personality.PersonalityEngine;

/** Builds the engine every autopilot uses. */
public final class Engines {
    private Engines() {}

    public static AutopilotEngine create() {
        return new PersonalityEngine(GoalLibrary::all, new StepPlanner());
    }
}
