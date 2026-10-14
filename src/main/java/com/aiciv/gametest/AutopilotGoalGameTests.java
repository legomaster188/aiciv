package com.aiciv.gametest;

import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Autopilot goal planning in the world (Task 2). Bot names start with TestApGoal. */
public final class AutopilotGoalGameTests {
    @GameTest
    public void classLoads(GameTestHelper helper) {
        helper.succeed();
    }

    private static void oakTree(GameTestHelper helper, int x, int z) {
        for (int y = 1; y <= 4; y++) helper.setBlock(x, y, z, Blocks.OAK_LOG);
    }

    @GameTest(maxTicks = 2000, padding = 24)
    public void woodStepMinesAndCrafts(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        oakTree(helper, 1, 1);
        oakTree(helper, 6, 6);
        GameTestKit.AutoSpawned s = GameTestKit.spawnAutopilot(helper, "TestApGoalWood", 3.5, 3.5, "autopilot-default");
        s.bot().player.getInventory().setItem(10, new ItemStack(Items.BREAD, 8));
        helper.succeedWhen(() -> {
            int pickaxes = InventoryOps.count(s.bot().player, st -> st.is(Items.WOODEN_PICKAXE));
            List<String> lines = GameTestKit.decisionLines(s.dir(), "TestApGoalWood");
            helper.assertTrue(pickaxes >= 1, "no wooden pickaxe yet; log: " + lines);
            long woodGoalLines = lines.stream().filter(l -> l.contains("\"mode\":\"autopilot\"")
                    && (l.contains("\"goal\":\"get_wood_tools\"") || l.contains("\"goal\":\"get_stone_tools\"")
                    || l.contains("\"goal\":\"get_iron_tools\""))).count();
            helper.assertTrue(woodGoalLines >= 2, "expected 2 decisions on a tool goal; log: " + lines);
            helper.assertTrue(lines.stream().noneMatch(l -> l.contains("\"error\"")), "log: " + lines);
            GameTestKit.remove(s);
        });
    }
}
