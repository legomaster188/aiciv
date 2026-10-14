package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 2: portals, the nether, fortresses and blazes. Bot names start with TestNeth. */
public final class NetherGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("nether")), "nether module missing");
        helper.succeed();
    }
}
