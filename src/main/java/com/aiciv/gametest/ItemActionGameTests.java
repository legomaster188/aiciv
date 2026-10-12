package com.aiciv.gametest;

import com.aiciv.actions.EatAction;
import com.aiciv.actions.EquipAction;
import com.aiciv.actions.GoToAction;
import com.aiciv.actions.PlaceAction;
import com.aiciv.actions.SmeltAction;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.reflex.ReflexEat;
import com.aiciv.world.InventoryOps;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Smelt, place, eat and equip, driven through a real bot on a small stone floor. */
public final class ItemActionGameTests {
    private static Bot bot(GameTestHelper helper, BotManager[] manager, String name) throws Exception {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        manager[0] = AiCivGameTests.scriptedManager(helper, new ScriptedBackend(List.of()), Files.createTempDirectory("aiciv-gametest"));
        Bot bot = manager[0].spawn(name, "scripted", "", helper.getLevel(), helper.absoluteVec(new Vec3(3.5, 1, 3.5)));
        helper.onEachTick(manager[0]::tickStart);
        return bot;
    }

    /** Nothing sends move packets for a bot, so its fall has to be added up the way the packet handler does it. */
    @GameTest(maxTicks = 300)
    public void takesFallDamageLikeAPlayer(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestFallDamage");
        boolean[] dropped = new boolean[1];
        // Wait out the spawn protection, then drop 6 blocks onto stone (the test box is not much taller): 6 - 3 = 3 damage.
        helper.runAfterDelay(80, () -> {
            Vec3 high = helper.absoluteVec(new Vec3(3.5, 7, 3.5));
            bot.player.teleportTo(high.x, high.y, high.z);
            dropped[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(dropped[0] && bot.player.onGround() && bot.player.getY() < helper.absoluteVec(new Vec3(0, 2, 0)).y,
                    "not landed yet: dropped=" + dropped[0] + " onGround=" + bot.player.onGround() + " y=" + bot.player.getY()
                            + " floor=" + helper.absoluteVec(new Vec3(0, 1, 0)).y + " health=" + bot.player.getHealth());
            float health = bot.player.getHealth();
            removeBot(manager[0], "TestFallDamage");
            helper.assertTrue(health >= 16.0F && health <= 18.0F, "expected about 3 fall damage, health is " + health);
        });
    }

    private static void removeBot(BotManager manager, String name) {
        try {
            manager.remove(name);
        } catch (com.aiciv.bot.BotException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String events(Bot bot) {
        return String.join("\n", bot.brain.events().lines());
    }

    private static boolean furnaceWithin4(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                if (helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.FURNACE)) return true;
            }
        }
        return false;
    }

    @GameTest(maxTicks = 600)
    public void smeltsRawIronWithCoal(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestSmeltIron");
        helper.setBlock(5, 1, 3, Blocks.FURNACE);
        bot.player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 2));
        bot.player.getInventory().setItem(1, new ItemStack(Items.COAL, 1));
        bot.executor.replace(List.of(new SmeltAction("iron_ingot", 2)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: smelt"), "smelt did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.IRON_INGOT, 2), "fewer than 2 iron ingots held");
            helper.assertTrue(!InventoryOps.has(bot.player, Items.RAW_IRON, 1), "raw iron left over");
            removeBot(manager[0], "TestSmeltIron");
        });
    }

    @GameTest(maxTicks = 400)
    public void smeltPlacesHeldFurnace(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestSmeltPlace");
        bot.player.getInventory().setItem(0, new ItemStack(Items.FURNACE, 1));
        bot.player.getInventory().setItem(1, new ItemStack(Items.RAW_COPPER, 1));
        bot.player.getInventory().setItem(2, new ItemStack(Items.COAL, 1));
        bot.executor.replace(List.of(new SmeltAction("raw_copper", 1)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(furnaceWithin4(helper), "no furnace was placed: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.COPPER_INGOT, 1), "no copper ingot yet: " + events(bot));
            removeBot(manager[0], "TestSmeltPlace");
        });
    }

    @GameTest(maxTicks = 100)
    public void smeltFailsWithoutAFurnace(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestSmeltNoFurn");
        bot.player.getInventory().setItem(0, new ItemStack(Items.RAW_GOLD, 1));
        bot.player.getInventory().setItem(1, new ItemStack(Items.COAL, 1));
        bot.executor.replace(List.of(new SmeltAction("gold_ingot", 1)), bot.context);
        helper.runAfterDelay(30, () -> {
            String text = events(bot);
            removeBot(manager[0], "TestSmeltNoFurn");
            helper.assertTrue(text.contains("needs a furnace or 8 cobblestone"), "expected a furnace report: " + text);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void placesCobblestoneAtTarget(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestPlaceCobble");
        bot.player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 3));
        bot.executor.replace(List.of(new PlaceAction("cobblestone", target.getX(), target.getY(), target.getZ())), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(6, 1, 3)).is(Blocks.COBBLESTONE), "no cobblestone at the target: " + events(bot));
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.COBBLESTONE)) == 3, "should hold 3 cobblestone");
            removeBot(manager[0], "TestPlaceCobble");
        });
    }

    @GameTest(maxTicks = 100)
    public void placeReportsOccupiedAndMissingItem(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestPlaceFail");
        helper.setBlock(6, 1, 3, Blocks.STONE);
        BlockPos taken = helper.absolutePos(new BlockPos(6, 1, 3));
        BlockPos free = helper.absolutePos(new BlockPos(6, 1, 5));
        bot.player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        bot.executor.replace(List.of(new PlaceAction("cobblestone", taken.getX(), taken.getY(), taken.getZ())), bot.context);
        helper.runAfterDelay(20, () -> {
            String first = events(bot);
            bot.executor.replace(List.of(new PlaceAction("oak_planks", free.getX(), free.getY(), free.getZ())), bot.context);
            helper.runAfterDelay(20, () -> {
                String text = events(bot);
                removeBot(manager[0], "TestPlaceFail");
                helper.assertTrue(first.contains("occupied"), "expected occupied: " + first);
                helper.assertTrue(text.contains("no oak_planks in inventory"), "expected missing item: " + text);
                helper.succeed();
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void eatsBreadWhenHungry(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestEatBread");
        bot.player.getFoodData().setFoodLevel(10);
        bot.player.getInventory().setItem(3, new ItemStack(Items.BREAD, 2));
        bot.player.getInventory().setItem(4, new ItemStack(Items.ROTTEN_FLESH, 5));
        bot.executor.replace(List.of(new EatAction()), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: eat"), "eat did not finish: " + events(bot));
            helper.assertTrue(bot.player.getFoodData().getFoodLevel() > 10, "food did not rise");
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.BREAD)) == 1, "bread count should be 1");
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.ROTTEN_FLESH)) == 5, "rotten flesh must not be eaten");
            removeBot(manager[0], "TestEatBread");
        });
    }

    @GameTest(maxTicks = 100)
    public void eatRefusesWhenNotHungry(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestEatFull");
        bot.player.getFoodData().setFoodLevel(20);
        bot.player.getInventory().setItem(0, new ItemStack(Items.BREAD, 2));
        bot.executor.replace(List.of(new EatAction()), bot.context);
        helper.runAfterDelay(20, () -> {
            String text = events(bot);
            removeBot(manager[0], "TestEatFull");
            helper.assertTrue(text.contains("not hungry"), "expected not hungry: " + text);
            helper.succeed();
        });
    }

    /** A reflex that takes over mid-meal stops the eating; the eat action starts the meal again afterwards. */
    @GameTest(maxTicks = 200)
    public void eatRestartsAfterAnInterruption(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestEatResume");
        bot.player.getFoodData().setFoodLevel(10);
        bot.player.getInventory().setItem(3, new ItemStack(Items.BREAD, 2));
        bot.executor.replace(List.of(new EatAction()), bot.context);
        helper.runAfterDelay(8, () -> {
            bot.executor.pause(bot.context); // what a reflex does; the controller resumes it on its next tick
            bot.player.stopUsingItem();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: eat"), "eat did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.BREAD)) == 1, "bread count should be 1");
            removeBot(manager[0], "TestEatResume");
        });
    }

    @GameTest(maxTicks = 100)
    public void reflexEatDoesNotCountChorusFruitAsFood(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestChorus");
        bot.player.getInventory().setItem(0, new ItemStack(Items.CHORUS_FRUIT, 4));
        boolean food = ReflexEat.hasFood(bot.player);
        removeBot(manager[0], "TestChorus");
        helper.assertTrue(!food, "chorus fruit teleports the eater; the eat reflex must not pick it");
        helper.succeed();
    }

    /** A goTo that just finished leaves the navigator "arrived"; smelt must still walk to a furnace out of reach. */
    @GameTest(maxTicks = 800)
    public void smeltWalksToAFarFurnaceAfterAGoTo(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestSmeltFar");
        helper.setBlock(7, 1, 7, Blocks.FURNACE); // about 10 blocks from the corner the bot walks to first
        bot.player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 1));
        bot.player.getInventory().setItem(1, new ItemStack(Items.COAL, 1));
        BlockPos here = helper.absolutePos(new BlockPos(0, 1, 0));
        bot.executor.replace(List.of(new GoToAction(here.getX(), here.getY(), here.getZ()), new SmeltAction("iron_ingot", 1)), bot.context);
        helper.runAfterDelay(799, () -> {
            if (manager[0].bot("TestSmeltFar").isPresent()) removeBot(manager[0], "TestSmeltFar");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!events(bot).contains("failed"), "smelt failed: " + events(bot));
            helper.assertTrue(events(bot).contains("smelted 1 iron_ingot"), "smelt did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.IRON_INGOT, 1), "no iron ingot held");
            removeBot(manager[0], "TestSmeltFar");
        });
    }

    /** Cancelling a smelt while the furnace is out of reach leaves the items in it; nothing is pulled back from afar. */
    @GameTest(maxTicks = 300)
    public void cancelledSmeltLeavesItemsInAFurnaceOutOfReach(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestSmeltAway");
        BlockPos furnacePos = new BlockPos(1, 1, 1);
        helper.setBlock(furnacePos, Blocks.FURNACE);
        bot.player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, 3));
        bot.player.getInventory().setItem(1, new ItemStack(Items.COAL, 2));
        bot.executor.replace(List.of(new SmeltAction("iron_ingot", 3)), bot.context);
        boolean[] stopped = {false};
        helper.runAfterDelay(299, () -> {
            if (manager[0].bot("TestSmeltAway").isPresent()) removeBot(manager[0], "TestSmeltAway");
        });
        helper.succeedWhen(() -> {
            AbstractFurnaceBlockEntity be = helper.getBlockEntity(furnacePos, AbstractFurnaceBlockEntity.class);
            if (!stopped[0]) {
                helper.assertTrue(!be.getItem(0).isEmpty(), "the furnace is not loaded yet");
                bot.player.moveTo(helper.getLevel(), helper.absoluteVec(new Vec3(7.5, 1, 7.5)), 0.0F);
                bot.executor.stop(bot.context);
                stopped[0] = true;
            }
            int inFurnace = be.getItem(0).getCount() + be.getItem(2).getCount();
            helper.assertTrue(inFurnace == 3, "the furnace holds " + inFurnace + " of the 3 items");
            helper.assertTrue(!InventoryOps.has(bot.player, Items.RAW_IRON, 1) && !InventoryOps.has(bot.player, Items.IRON_INGOT, 1),
                    "items were pulled back from a furnace 9 blocks away");
            removeBot(manager[0], "TestSmeltAway");
        });
    }

    @GameTest(maxTicks = 100)
    public void equipsIronChestplate(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = bot(helper, manager, "TestEquipChest");
        bot.player.getInventory().setItem(5, new ItemStack(Items.IRON_CHESTPLATE, 1));
        bot.player.getInventory().setItem(6, new ItemStack(Items.DIAMOND_SWORD, 1));
        bot.executor.replace(List.of(new EquipAction("iron_chestplate"), new EquipAction("diamond_sword")), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "chestplate not worn: " + events(bot));
            helper.assertTrue(bot.player.getMainHandItem().is(Items.DIAMOND_SWORD), "sword not in hand: " + events(bot));
            helper.assertTrue(InventoryOps.count(bot.player, s -> s.is(Items.IRON_CHESTPLATE)) == 0, "chestplate should leave the inventory");
            removeBot(manager[0], "TestEquipChest");
        });
    }
}
