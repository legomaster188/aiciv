package com.aiciv.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Autopilot world goals: home, storage, farm, trade, defence, manners (Task 5). Bot names start with TestApWorld. */
public final class AutopilotWorldGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }
}
