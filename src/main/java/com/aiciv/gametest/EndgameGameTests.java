package com.aiciv.gametest;

import com.aiciv.modules.ToolModule;
import com.aiciv.modules.ToolModules;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Task 11: the whole endgame, played by scripted models. Bot names start with TestEnd. */
public final class EndgameGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        List<String> names = ToolModules.ALL.stream().map(ToolModule::name).toList();
        for (String name : List.of("dimension", "nether", "stronghold", "dragon", "vehicles", "flight", "items", "animals", "stations")) {
            helper.assertTrue(names.contains(name), name + " module missing");
        }
        helper.succeed();
    }
}
