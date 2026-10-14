package com.aiciv.gametest;

import com.aiciv.modules.ToolModules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 8: taming and pets. Bot names start with TestPet. */
public final class AnimalGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("animals")), "animals module missing");
        helper.succeed();
    }
}
