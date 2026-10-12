package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Building blueprints, templates and named structures (Task 2). Bot names start with TestBuild. */
public final class BuildGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("building")), "the building module is not registered");
        helper.succeed();
    }
}
