package com.aiciv.gametest;

import com.aiciv.actions.EatAction;
import com.aiciv.actions.EquipAction;
import com.aiciv.actions.PlaceAction;
import com.aiciv.actions.SmeltAction;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.llm.ScriptedBackend;
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
