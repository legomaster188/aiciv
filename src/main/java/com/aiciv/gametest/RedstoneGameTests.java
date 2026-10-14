package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 10: redstone blueprints and use_block. Bot names start with TestRed. */
public final class RedstoneGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("building")), "building module missing");
        helper.succeed();
    }
}
