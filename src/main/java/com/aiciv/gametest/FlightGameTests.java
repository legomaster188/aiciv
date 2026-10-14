package com.aiciv.gametest;

import com.aiciv.bot.Bot;
import com.aiciv.flight.FlyToAction;
import com.aiciv.flight.ThrowPearlAction;
import com.aiciv.modules.ToolModules;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Task 6: elytra and ender pearls. Bot names start with TestFly. */
public final class FlightGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("flight")), "flight module missing");
        helper.succeed();
    }

    private static int pearls(Bot bot) {
        return InventoryOps.count(bot.player, s -> s.is(Items.ENDER_PEARL));
    }

    /** Two stone areas with a gap between them: the bot's 8 x 8 floor and a 3 x 3 platform 14 blocks east at the same height. */
    private static void pearlArena(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int x = 15; x <= 17; x++) for (int z = 2; z <= 4; z++) helper.setBlock(x, 0, z, Blocks.STONE);
    }

    private static GameTestKit.Spawned pearlBot(GameTestHelper helper, String name, float health) {
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 2.5, 3.5);
        s.bot().player.getInventory().add(new ItemStack(Items.ENDER_PEARL, 2));
        s.bot().player.setHealth(health);
        return s;
    }

    private static BlockPos landing(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(16, 0, 3));
    }

    @GameTest(maxTicks = 900, padding = 24)
    public void flyToPlatform(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = -10; x <= 44; x++) for (int z = -10; z <= 44; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, -10, -10, 44, 44);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFlyElytra", 6.5, 6.5);
        for (int y = 1; y <= 12; y++) helper.setBlock(2, y, 2, Blocks.STONE);
        Bot bot = s.bot();
        bot.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        bot.player.getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 4));
        bot.player.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 13, 2.5)), 0f);
        BlockPos target = helper.absolutePos(new BlockPos(20, 1, 20));
        bot.executor.replace(List.of(new FlyToAction(target.getX(), target.getZ())), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: flew to"), "not there yet: " + events);
            double away = Math.hypot(target.getX() + 0.5 - bot.player.getX(), target.getZ() + 0.5 - bot.player.getZ());
            helper.assertTrue(away <= 16, "landed " + away + " blocks from the target at " + bot.player.position());
            helper.assertTrue(bot.player.isAlive(), "the bot died; " + events);
            helper.assertTrue(!bot.player.isFallFlying(), "still gliding");
            release.run();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void pearlLandsOnPlatform(GameTestHelper helper) {
        pearlArena(helper);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, -1, -1, 20, 8);
        GameTestKit.Spawned s = pearlBot(helper, "TestFlyPearl", 20f);
        Bot bot = s.bot();
        BlockPos landing = landing(helper);
        // A new player takes no damage until its client has loaded (60 ticks); a bot that lives longer pays the 5 health.
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(bot.player.connection.hasClientLoaded(), "the bot is still invulnerable");
            bot.executor.replace(List.of(new ThrowPearlAction(landing.getX(), landing.getY(), landing.getZ())), bot.context);
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: pearled to"), "not pearled yet: " + events);
            double away = bot.player.position().distanceTo(new Vec3(landing.getX() + 0.5, landing.getY() + 1, landing.getZ() + 0.5));
            helper.assertTrue(away <= 2.5, "ended " + away + " blocks from the platform at " + bot.player.position());
            helper.assertTrue(bot.player.getHealth() <= 16, "health " + bot.player.getHealth() + "; the pearl costs 5; " + events);
            helper.assertTrue(pearls(bot) == 1, "pearls left: " + pearls(bot));
            release.run();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100, padding = 24)
    public void pearlRefusedAtLowHealth(GameTestHelper helper) {
        pearlArena(helper);
        GameTestKit.Spawned s = pearlBot(helper, "TestFlyHurt", 6f);
        Bot bot = s.bot();
        BlockPos landing = landing(helper);
        bot.executor.replace(List.of(new ThrowPearlAction(landing.getX(), landing.getY(), landing.getZ())), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("too hurt to pearl"), "not refused: " + events);
            helper.assertTrue(pearls(bot) == 2, "pearls changed to " + pearls(bot));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100, padding = 24)
    public void pearlNeedsSightOfLanding(GameTestHelper helper) {
        pearlArena(helper);
        for (int z = -2; z <= 10; z++) for (int y = 1; y <= 4; y++) helper.setBlock(8, y, z, Blocks.STONE);
        GameTestKit.Spawned s = pearlBot(helper, "TestFlyBlind", 20f);
        Bot bot = s.bot();
        BlockPos landing = landing(helper);
        bot.executor.replace(List.of(new ThrowPearlAction(landing.getX(), landing.getY(), landing.getZ())), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("can't see where to land"), "not refused: " + events);
            helper.assertTrue(pearls(bot) == 2, "pearls changed to " + pearls(bot));
            GameTestKit.remove(s);
        });
    }
}
