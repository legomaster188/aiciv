package com.aiciv.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** The shared world toolkit (Task 1): placed-block memory, block and entity use, menus, finding and approaching. */
public final class ToolkitGameTests {
    @GameTest(maxTicks = 40)
    public void kitSpawnsAndRemovesABot(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestKitSpawn", 3.5, 3.5);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(s.bot().context.attachments() == s.bot().attachments, "the live context hands out the bot's attachments");
            s.bot().context.event("a test event");
            helper.assertTrue(GameTestKit.events(s.bot()).contains("a test event"), "event() did not reach the log: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
            helper.assertTrue(s.manager().bot("TestKitSpawn").isEmpty(), "the bot is still online");
            helper.succeed();
        });
    }
}
