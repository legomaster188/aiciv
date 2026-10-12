package com.aiciv.gametest;

import com.aiciv.modules.ToolModule;
import com.aiciv.modules.ToolModules;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The milestone-3 acceptance run (Task 8). Bot names start with TestCiv. */
public final class CivGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        List<String> names = ToolModules.ALL.stream().map(ToolModule::name).toList();
        for (String name : List.of("building", "storage", "combat", "trade", "farm", "utility")) {
            helper.assertTrue(names.contains(name), "the " + name + " module is not registered: " + names);
        }
        helper.succeed();
    }
}
