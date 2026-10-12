package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Trading with villagers (Task 5). Bot names start with TestTrade. */
public final class TradeGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("trade")), "the trade module is not registered");
        helper.succeed();
    }
}
