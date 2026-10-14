package com.aiciv.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Autopilot goal planning in the world (Task 2). Bot names start with TestApGoal. */
public final class AutopilotGoalGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }
}
