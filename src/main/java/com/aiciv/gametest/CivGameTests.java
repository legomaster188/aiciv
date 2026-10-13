package com.aiciv.gametest;

import static com.aiciv.gametest.GameTestKit.decisionLines;
import static com.aiciv.gametest.GameTestKit.turn;

import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.building.StructureRegistry;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.ToolModules;
import com.aiciv.world.InventoryOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** The milestone-3 acceptance run (Task 8). Bot names start with TestCiv. */
public final class CivGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        List<String> names = ToolModules.ALL.stream().map(ToolModule::name).toList();
        for (String name : List.of("building", "storage", "combat", "trade", "farm", "utility")) {
            helper.assertTrue(names.contains(name), "the " + name + " module is not registered: " + names);
        }
        helper.succeed();
    }

    private static final int SIZE = 16;

    private static String layer(String... rows) {
        return "[\"" + String.join("\",\"", rows) + "\"]";
    }

    /** Counts of cobblestone, dirt and oak logs across every chest in the arena, or null when there is no chest. */
    private static int[] chestContents(GameTestHelper helper) {
        int[] total = new int[3];
        boolean found = false;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 1; y <= 3; y++) {
                for (int z = 0; z < SIZE; z++) {
                    BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, y, z)));
                    if (!(be instanceof Container chest) || !helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.CHEST)) continue;
                    found = true;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        ItemStack stack = chest.getItem(i);
                        if (stack.is(Items.COBBLESTONE)) total[0] += stack.getCount();
                        else if (stack.is(Items.DIRT)) total[1] += stack.getCount();
                        else if (stack.is(Items.OAK_LOG)) total[2] += stack.getCount();
                        else if (!stack.isEmpty()) total[0] += 1000; // anything else in the chest is a failure
                    }
                }
            }
        }
        return found ? total : null;
    }

    /** A scripted bot builds a 5x5 hut from a blueprint, stores items in a chest it places, and sleeps in a bed it carries. */
    @GameTest(maxTicks = 6000, padding = 24, environment = "aiciv:night")
    public void scriptedBotBuildsStoresAndSleeps(GameTestHelper helper) throws Exception {
        GameTestKit.clearBarriers(helper, SIZE);
        GameTestKit.floor(helper, SIZE, Blocks.STONE);
        StructureRegistry.of(helper.getLevel().getServer()).store().remove("ada_hut");
        BlockPos at = helper.absolutePos(new BlockPos(4, 1, 3));
        String wall = layer("pp.pp", "p...p", "p...p", "p...p", "ppppp");
        String roof = layer("ppppp", "ppppp", "ppppp", "ppppp", "ppppp");
        String build = "{\"name\":\"ada_hut\",\"facing\":\"S\",\"x\":" + at.getX() + ",\"y\":" + at.getY() + ",\"z\":" + at.getZ()
                + ",\"legend\":{\"p\":\"oak_planks\"},\"layers\":[" + wall + "," + wall + "," + roof + "]}";
        ScriptedBackend backend = new ScriptedBackend(List.of(
                turn("build", build),
                turn("place_chest", "{}", "deposit", "{\"items\":\"cobblestone, dirt, oak_log\"}"),
                turn("sleep", "{}")));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = AiCivGameTests.scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestCivAda", "scripted", "A builder.", helper.getLevel(), helper.absoluteVec(new Vec3(12.5, 1, 12.5)));
        Inventory inv = bot.player.getInventory();
        inv.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        inv.setItem(1, new ItemStack(Items.CHEST));
        inv.setItem(2, new ItemStack(Items.BED.white()));
        inv.setItem(3, new ItemStack(Items.COBBLESTONE, 20));
        inv.setItem(4, new ItemStack(Items.DIRT, 10));
        inv.setItem(5, new ItemStack(Items.OAK_LOG, 5));
        inv.setItem(6, new ItemStack(Items.STONE_PICKAXE));
        boolean[] slept = new boolean[1];
        int[] tick = new int[1];
        helper.onEachTick(() -> {
            tick[0]++;
            manager.tickStart();
            manager.tickEnd();
            slept[0] |= bot.player.isSleeping();
        });
        helper.runAfterDelay(5999, () -> {
            if (manager.bot("TestCivAda").isPresent()) AiCivGameTests.removeBot(manager, "TestCivAda");
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(StructureRegistry.of(helper.getLevel().getServer()).store().get("ada_hut").isPresent(), "ada_hut is not registered: " + events);
            int planks = 0;
            for (int layer = 0; layer < 3; layer++) {
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 5; col++) {
                        if (layer < 2 && row == 0 && col == 2) continue; // the door gap
                        BlockPos cell = new BlockPos(4 - col, 1 + layer, 3 + row);
                        boolean hollow = layer < 2 && row >= 1 && row <= 3 && col >= 1 && col <= 3;
                        if (hollow) continue;
                        if (helper.getBlockState(cell).is(Blocks.OAK_PLANKS)) planks++;
                    }
                }
            }
            helper.assertTrue(planks == 55, "the hut has " + planks + " of 55 planks: " + events);
            int[] chest = chestContents(helper);
            helper.assertTrue(chest != null && chest[0] == 20 && chest[1] == 10 && chest[2] == 5,
                    "chest contents (cobble, dirt, logs): " + (chest == null ? "no chest" : java.util.Arrays.toString(chest)) + "; " + events);
            helper.assertTrue(slept[0] || events.contains("slept"), "the bot never slept: " + events);
            helper.assertTrue(InventoryOps.has(bot.player, Items.STONE_PICKAXE, 1), "the stone pickaxe is gone");
            List<String> decisions = decisionLines(dir, "TestCivAda");
            helper.assertTrue(decisions.size() >= 3, "only " + decisions.size() + " decisions logged");
            for (String line : decisions) helper.assertTrue(!line.contains("\"error\""), "a decision logged an error: " + line);
            System.out.println("[CivGameTests] scriptedBotBuildsStoresAndSleeps finished at tick " + tick[0]);
            AiCivGameTests.removeBot(manager, "TestCivAda");
        });
    }
}
