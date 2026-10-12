package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Sleep, doors, switches, enchanting and brewing (Task 7). Bot names start with TestUtil. */
public final class UtilityGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("utility")), "the utility module is not registered");
        helper.succeed();
    }
}
