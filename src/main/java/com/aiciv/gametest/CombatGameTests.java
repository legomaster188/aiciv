package com.aiciv.gametest;

import static com.aiciv.gametest.AiCivGameTests.actionBot;
import static com.aiciv.gametest.AiCivGameTests.events;
import static com.aiciv.gametest.AiCivGameTests.removeBot;

import com.aiciv.actions.AttackAction;
import com.aiciv.actions.GoToAction;
import com.aiciv.actions.MineAction;
import com.aiciv.actions.StopAction;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.nav.NavStatus;
import com.aiciv.reflex.ReflexPolicy;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Combat and reflex game tests. Each mob type here (zombie, husk, skeleton, stray) is used by one test only. */
public final class CombatGameTests {
    private static int easyUsers;
    private static Difficulty difficultyBefore;

    /** Hostile mobs vanish in peaceful, so tests that need them switch to easy and put the setting back after. */
    private static void easy(MinecraftServer server) {
        if (easyUsers++ == 0) {
            difficultyBefore = server.overworld().getDifficulty();
            server.setDifficulty(Difficulty.EASY, true);
        }
    }

    private static void restoreDifficulty(MinecraftServer server) {
        if (--easyUsers == 0) server.setDifficulty(difficultyBefore, true);
    }

    private static void floor(GameTestHelper helper, int sizeX, int sizeZ) {
        for (int x = 0; x < sizeX; x++) for (int z = 0; z < sizeZ; z++) helper.setBlock(x, 0, z, Blocks.DIRT);
    }

    /** Cleans up if the test times out before it does so itself. */
    private static void cleanupAtTimeout(GameTestHelper helper, int maxTicks, BotManager[] manager, String bot, Mob mob) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.runAfterDelay(maxTicks - 1, () -> {
            if (mob != null) restoreDifficulty(server);
            if (mob != null) mob.discard();
            if (manager[0] != null && manager[0].bot(bot).isPresent()) removeBot(manager[0], bot);
        });
    }

    @GameTest(maxTicks = 400)
    public void killsZombieWithSword(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        floor(helper, 12, 8);
        easy(server);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestKillZombie", new Vec3(2.5, 1, 3.5));
        Mob zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(6.5, 1, 3.5));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // daylight must not do the killing
        zombie.setPersistenceRequired();
        bot.player.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        bot.player.getInventory().setItem(4, new ItemStack(Items.IRON_SWORD));
        bot.executor.replace(List.of(new AttackAction("zombie")), bot.context);
        cleanupAtTimeout(helper, 400, manager, "TestKillZombie", zombie);
        helper.succeedWhen(() -> {
            helper.assertTrue(!zombie.isAlive(), "the zombie is still alive; " + events(bot));
            helper.assertTrue(events(bot).contains("finished: attack zombie"), "attack did not finish: " + events(bot));
            helper.assertTrue(bot.player.getMainHandItem().is(Items.IRON_SWORD), "bot did not hold the sword");
            restoreDifficulty(server);
            removeBot(manager[0], "TestKillZombie");
        });
    }

    @GameTest(maxTicks = 600)
    public void hungryBotEatsThenKeepsMining(GameTestHelper helper) throws Exception {
        floor(helper, 8, 8);
        for (int x = 2; x <= 5; x++) helper.setBlock(x, 1, 2, Blocks.STONE);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestHungryMiner", new Vec3(3.5, 1, 5.5));
        bot.player.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        bot.player.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
        bot.executor.replace(List.of(new MineAction("stone", 3)), bot.context);
        // The bot gets hungry while it is already walking up to the rock.
        helper.runAfterDelay(10, () -> {
            bot.player.getFoodData().setFoodLevel(8);
            bot.player.getFoodData().setSaturation(0.0F);
        });
        cleanupAtTimeout(helper, 600, manager, "TestHungryMiner", null);
        helper.succeedWhen(() -> {
            int cobble = InventoryOps.count(bot.player, s -> s.is(Items.COBBLESTONE));
            helper.assertTrue(events(bot).contains("finished: mine"), "mine did not finish: " + events(bot));
            helper.assertTrue(cobble >= 3, "bot holds only " + cobble + " cobblestone; " + events(bot));
            helper.assertTrue(bot.player.getFoodData().getFoodLevel() > 8, "bot is still hungry; " + events(bot));
            helper.assertTrue(events(bot).contains("reflex: ate"), "no eat reflex was logged: " + events(bot));
            helper.assertTrue(!bot.executor.paused(), "the executor is still paused");
            removeBot(manager[0], "TestHungryMiner");
        });
    }

    @GameTest(maxTicks = 300)
    public void fleesWhenLowHealth(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        floor(helper, 16, 28);
        easy(server);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestFleeLowHp", new Vec3(8.5, 1, 6.5));
        Mob husk = helper.spawn(EntityTypes.HUSK, new Vec3(8.5, 1, 3.5));
        husk.setNoAi(true); // it stays put, so any distance the bot gains is the bot running
        husk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        husk.setPersistenceRequired();
        bot.player.setHealth(5.0F);
        bot.player.setLastHurtByMob(husk);
        cleanupAtTimeout(helper, 300, manager, "TestFleeLowHp", husk);
        helper.succeedWhen(() -> {
            double apart = bot.player.distanceTo(husk);
            helper.assertTrue(apart >= 8.0, "bot is only " + apart + " blocks from the husk; " + events(bot));
            helper.assertTrue(events(bot).contains("reflex: fled from husk"), "no flee reflex was logged: " + events(bot));
            restoreDifficulty(server);
            husk.discard();
            removeBot(manager[0], "TestFleeLowHp");
        });
    }

    /** A model reply that lands mid-flight replaces the queued actions but must not stop the flight itself. */
    @GameTest(maxTicks = 300)
    public void aNewDecisionDoesNotCancelAFlight(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        floor(helper, 16, 28);
        easy(server);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestFleeReply", new Vec3(8.5, 1, 6.5));
        Mob skeleton = helper.spawn(EntityTypes.SKELETON, new Vec3(8.5, 1, 3.5));
        skeleton.setNoAi(true);
        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        skeleton.setPersistenceRequired();
        BlockPos side = helper.absolutePos(new BlockPos(14, 1, 6));
        bot.executor.replace(List.of(new GoToAction(side.getX(), side.getY(), side.getZ())), bot.context);
        helper.runAfterDelay(3, () -> {
            bot.player.setHealth(5.0F);
            bot.player.setLastHurtByMob(skeleton);
        });
        boolean[] replaced = {false};
        helper.runAfterDelay(8, () -> {
            replaced[0] = bot.reflexes.active() == ReflexPolicy.Reflex.FLEE;
            bot.executor.replace(List.of(new StopAction()), bot.context);
        });
        cleanupAtTimeout(helper, 300, manager, "TestFleeReply", skeleton);
        helper.succeedWhen(() -> {
            helper.assertTrue(replaced[0], "the reply did not land mid-flight; " + events(bot));
            helper.assertTrue(!events(bot).contains("could not flee"), "the flight was cut short: " + events(bot));
            helper.assertTrue(events(bot).contains("reflex: fled from skeleton"), "no flee reflex was logged: " + events(bot));
            restoreDifficulty(server);
            skeleton.discard();
            removeBot(manager[0], "TestFleeReply");
        });
    }

    /** Pausing a bot in the middle of a reflex stands it still; nothing keeps steering it. */
    @GameTest(maxTicks = 200)
    public void pausingMidFlightStandsTheBotStill(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        floor(helper, 16, 28);
        easy(server);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestPauseFlee", new Vec3(8.5, 1, 6.5));
        Mob stray = helper.spawn(EntityTypes.STRAY, new Vec3(8.5, 1, 3.5));
        stray.setNoAi(true);
        stray.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        stray.setPersistenceRequired();
        bot.player.setHealth(5.0F);
        bot.player.setLastHurtByMob(stray);
        boolean[] fleeing = {false};
        helper.runAfterDelay(5, () -> {
            fleeing[0] = bot.reflexes.active() == ReflexPolicy.Reflex.FLEE;
            try {
                manager[0].setPaused("TestPauseFlee", true);
            } catch (com.aiciv.bot.BotException e) {
                throw new IllegalStateException(e);
            }
        });
        cleanupAtTimeout(helper, 200, manager, "TestPauseFlee", stray);
        helper.runAfterDelay(25, () -> {
            boolean moving = bot.player.zza != 0.0F || bot.player.xxa != 0.0F;
            boolean reflex = bot.reflexes.active() != ReflexPolicy.Reflex.NONE;
            boolean navigating = bot.navigator.status().state() != NavStatus.State.IDLE;
            restoreDifficulty(server);
            stray.discard();
            removeBot(manager[0], "TestPauseFlee");
            helper.assertTrue(fleeing[0], "the bot was not fleeing when paused");
            helper.assertTrue(!moving && !reflex && !navigating, "paused bot still moving=" + moving + " reflex=" + reflex + " navigating=" + navigating);
            helper.succeed();
        });
    }

    /** A ring of dirt around x 4..6, z 4..6 from y 1 to {@code top}, so liquid inside cannot flow out. */
    private static void ring(GameTestHelper helper, int top) {
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) {
                boolean edge = x == 3 || x == 7 || z == 3 || z == 7;
                for (int y = 1; y <= top; y++) if (edge) helper.setBlock(x, y, z, Blocks.DIRT);
            }
        }
    }

    @GameTest(maxTicks = 300)
    public void stepsOutOfLava(GameTestHelper helper) throws Exception {
        floor(helper, 11, 11);
        ring(helper, 1);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.LAVA);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestLavaEscape", new Vec3(5.5, 1, 5.5));
        bot.player.setHealth(20.0F);
        cleanupAtTimeout(helper, 300, manager, "TestLavaEscape", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("reflex: got out of lava or fire"), "no escape was logged: " + events(bot));
            helper.assertTrue(!bot.player.isInLava(), "bot is still in lava at " + bot.player.position());
            helper.assertTrue(bot.player.getHealth() > 0, "bot died in the lava");
            removeBot(manager[0], "TestLavaEscape");
        });
    }

    @GameTest(maxTicks = 300)
    public void surfacesWhenOutOfAir(GameTestHelper helper) throws Exception {
        floor(helper, 11, 11);
        ring(helper, 6);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) for (int y = 1; y <= 4; y++) helper.setBlock(x, y, z, Blocks.WATER);
        BotManager[] manager = new BotManager[1];
        Bot bot = actionBot(helper, manager, "TestSurface", new Vec3(5.5, 1, 5.5));
        bot.player.setAirSupply(60);
        cleanupAtTimeout(helper, 300, manager, "TestSurface", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(!bot.player.isUnderWater(), "bot is still under water at " + bot.player.position());
            helper.assertTrue(events(bot).contains("reflex: swam up for air"), "no surfacing was logged: " + events(bot));
            removeBot(manager[0], "TestSurface");
        });
    }
}
