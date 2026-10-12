package com.aiciv.gametest;

import com.aiciv.actions.CraftAction;
import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.craft.CraftPlanner;
import com.aiciv.craft.RecipeBook;
import com.aiciv.craft.RecipeOption;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.world.InventoryOps;
import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class CraftGameTests {
    private static Bot craftBot(GameTestHelper helper, BotManager[] manager, String name) throws Exception {
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

    private static boolean tableWithin4(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                if (helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.CRAFTING_TABLE)) return true;
            }
        }
        return false;
    }

    @GameTest(maxTicks = 400)
    public void craftsWoodenPickaxeFromLogsInOneAction(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = craftBot(helper, manager, "TestCraftPick");
        bot.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        bot.executor.replace(List.of(new CraftAction("wooden_pickaxe", 1)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: crafted 1 wooden_pickaxe"), "craft did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.WOODEN_PICKAXE, 1), "no wooden pickaxe held");
            helper.assertTrue(tableWithin4(helper), "no crafting table was placed");
            helper.assertTrue(events(bot).contains("crafting_table"), "summary should mention the table: " + events(bot));
            removeBot(manager[0], "TestCraftPick");
        });
    }

    @GameTest(maxTicks = 100)
    public void craftFailsWithMissingMaterials(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = craftBot(helper, manager, "TestCraftMissing");
        bot.player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        bot.executor.replace(List.of(new CraftAction("stone_pickaxe", 1)), bot.context);
        helper.runAfterDelay(20, () -> {
            String text = events(bot);
            boolean noTable = !tableWithin4(helper);
            removeBot(manager[0], "TestCraftMissing");
            helper.assertTrue(text.contains("failed: craft stone_pickaxe") && text.contains("cobblestone"), "expected a missing cobblestone report: " + text);
            helper.assertTrue(noTable, "a table was placed although the craft could not succeed");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void craftsSticksFromPlanksWithoutATable(GameTestHelper helper) throws Exception {
        BotManager[] manager = new BotManager[1];
        Bot bot = craftBot(helper, manager, "TestCraftSticks");
        bot.player.getInventory().setItem(0, new ItemStack(Items.BIRCH_PLANKS, 4));
        bot.executor.replace(List.of(new CraftAction("Sticks", 8)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(events(bot).contains("finished: crafted 8 stick"), "craft did not finish: " + events(bot));
            helper.assertTrue(InventoryOps.has(bot.player, Items.STICK, 8), "fewer than 8 sticks held");
            helper.assertTrue(!tableWithin4(helper), "a table was placed for a 2x2 recipe");
            removeBot(manager[0], "TestCraftSticks");
        });
    }

    @GameTest(maxTicks = 100)
    public void recipeBookReadsVanillaRecipes(GameTestHelper helper) {
        List<RecipeOption> recipes = RecipeBook.load(helper.getLevel());
        RecipeOption pickaxe = recipes.stream().filter(r -> r.output().equals("wooden_pickaxe")).findFirst().orElse(null);
        RecipeOption stick = recipes.stream().filter(r -> r.output().equals("stick")).findFirst().orElse(null);
        RecipeOption table = recipes.stream().filter(r -> r.output().equals("crafting_table")).findFirst().orElse(null);
        helper.assertTrue(pickaxe != null && stick != null && table != null, "missing a core recipe among " + recipes.size());
        helper.assertTrue(pickaxe.needsTable() && pickaxe.ingredients().size() == 5, "pickaxe: " + pickaxe);
        helper.assertTrue(!stick.needsTable() && stick.outputCount() == 4 && stick.ingredients().size() == 2, "stick: " + stick);
        helper.assertTrue(!table.needsTable() && table.ingredients().size() == 4, "table: " + table);
        helper.assertTrue(pickaxe.ingredients().get(0).contains("oak_planks") && pickaxe.ingredients().get(0).contains("birch_planks"), "pickaxe head should accept any planks");
        helper.assertTrue(CraftPlanner.canMake("wooden_pickaxe", 1, java.util.Map.of("spruce_log", 3), recipes, false), "3 logs should make a pickaxe");
        helper.assertTrue(!CraftPlanner.canMake("wooden_pickaxe", 1, java.util.Map.of("spruce_log", 2), recipes, false), "2 logs should not");
        helper.succeed();
    }
}
