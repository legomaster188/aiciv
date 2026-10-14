package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 3: eyes of ender, the stronghold and the End portal. Bot names start with TestHold. */
public final class StrongholdGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("stronghold")), "stronghold module missing");
        helper.succeed();
    }
}
