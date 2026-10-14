package com.aiciv.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Autopilot chat (Task 4). Bot names start with TestApChat. */
public final class AutopilotChatGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }
}
