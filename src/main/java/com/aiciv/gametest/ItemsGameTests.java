package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 7: buckets, bone meal, fire, potions, throwables, maps and compasses. Bot names start with TestItem. */
public final class ItemsGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("items")), "items module missing");
        helper.succeed();
    }
}
