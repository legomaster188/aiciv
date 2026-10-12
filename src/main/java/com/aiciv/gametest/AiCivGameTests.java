package com.aiciv.gametest;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.nav.NavBudget;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class AiCivGameTests {
    static void stoneFloor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    @GameTest(maxTicks = 200)
    public void fakePlayerJoinsWalksAndLeaves(GameTestHelper helper) {
        stoneFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestWalker", () -> {});
        Vec3 start = helper.absoluteVec(new Vec3(0.5, 1, 3.5));
        // Yaw -90 faces +X (east).
        bot.moveTo(helper.getLevel(), start, -90.0F);
        helper.onEachTick(() -> {
            bot.setYRot(-90.0F);
            bot.zza = 1.0F;
        });
        helper.runAfterDelay(25, () -> {
            double moved = bot.position().x - start.x;
            server.getPlayerList().remove(bot);
            helper.assertTrue(moved > 2.0, "bot moved only " + moved + " blocks");
            helper.assertTrue(server.getPlayerList().getPlayerByName("TestWalker") == null, "bot is still in the player list");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void navigatorCrossesObstacleCourse(GameTestHelper helper) {
        stoneFloor(helper);
        // Raised ground from x = 5: the bot must jump up one block.
        for (int x = 5; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        // Magma across the straight line: the bot must walk around it.
        for (int z = 0; z <= 2; z++) helper.setBlock(3, 0, z, Blocks.MAGMA_BLOCK);
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, "TestNavigator", () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(0.5, 1, 1.5)), 0.0F);
        NavBudget budget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
        LevelNavigator nav = new LevelNavigator(bot, budget);
        BlockPos goal = helper.absolutePos(new BlockPos(6, 2, 1));
        nav.goTo(new NearGoal(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.5));
        helper.onEachTick(() -> {
            budget.tick();
            nav.tick();
        });
        helper.succeedWhen(() -> {
            NavStatus s = nav.status();
            helper.assertTrue(s.state() == NavStatus.State.ARRIVED, "navigator is " + s.state() + " " + s.reason());
            helper.assertTrue(bot.getY() > goal.getY() - 0.1, "bot is below the raised ground at y " + bot.getY());
            server.getPlayerList().remove(bot);
        });
    }
}
