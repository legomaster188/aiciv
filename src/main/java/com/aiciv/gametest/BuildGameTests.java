package com.aiciv.gametest;

import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.building.StructureRegistry;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Building blueprints, templates and named structures (Task 2). Bot names start with TestBuild. */
public final class BuildGameTests {
    /** Three layers of 3x3: two rings with a hollow middle, then a full roof; 23 cobblestone, 4 air cells. */
    private static final String HUT_LAYERS = "[[\"ccc\",\"c.c\",\"c.c\"],[\"ccc\",\"c.c\",\"c.c\"],[\"ccc\",\"ccc\",\"ccc\"]]";

    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("building")), "the building module is not registered");
        helper.succeed();
    }

    private static JsonObject hutArgs(GameTestHelper helper, String name) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        return JsonParser.parseString("{\"name\":\"" + name + "\",\"layers\":" + HUT_LAYERS + ",\"legend\":{\"c\":\"cobblestone\"},"
                + "\"x\":" + at.getX() + ",\"y\":" + at.getY() + ",\"z\":" + at.getZ() + ",\"facing\":\"S\"}").getAsJsonObject();
    }

    private static void run(Bot bot, String tool, JsonObject args) {
        ActionFactory.Result r = ActionFactory.create(ToolCall.of(tool, args));
        if (r instanceof ActionFactory.Result.Ok ok) bot.executor.replace(List.of(ok.action()), bot.context);
        else throw new IllegalStateException(tool + " was refused: " + ((ActionFactory.Result.Invalid) r).reason());
    }

    private static int cobblestone(Bot bot) {
        return InventoryOps.count(bot.player, s -> s.is(Items.COBBLESTONE));
    }

    private static GameTestKit.Spawned hutBot(GameTestHelper helper, String name, int cobble) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, cobble));
        return s;
    }

    private static void forget(GameTestHelper helper, String structure) {
        StructureRegistry.of(helper.getLevel().getServer()).store().remove(structure);
    }

    @GameTest(maxTicks = 1200)
    public void buildsBlueprintHut(GameTestHelper helper) {
        forget(helper, "test_hut");
        GameTestKit.Spawned s = hutBot(helper, "TestBuildHut", 30);
        run(s.bot(), "build", hutArgs(helper, "test_hut"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: build test_hut"), "not finished yet: " + GameTestKit.events(s.bot()));
            for (int layer = 0; layer < 3; layer++) {
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        boolean air = layer < 2 && col == 1 && row > 0;
                        // South-facing: row r is z = 3 + r and column c is x = 3 - c.
                        BlockPos cell = new BlockPos(3 - col, 1 + layer, 3 + row);
                        Block expected = air ? Blocks.AIR : Blocks.COBBLESTONE;
                        helper.assertTrue(helper.getBlockState(cell).is(expected), "layer " + layer + " row " + row + " col " + col + " should be " + expected);
                    }
                }
            }
            helper.assertTrue(cobblestone(s.bot()) == 7, "the bot should hold 7 cobblestone, holds " + cobblestone(s.bot()));
            var found = StructureRegistry.of(helper.getLevel().getServer()).store().get("test_hut");
            helper.assertTrue(found.isPresent() && found.get().owner().equals("TestBuildHut"), "the structure is not registered");
            String text = SnapshotFormatter.format(Perceiver.capture(s.bot().player, "none", "nothing yet", p -> p instanceof AiPlayer,
                    s.bot().knownBlocks, s.bot().attachments), SnapshotFormatter.MAX_CHARS);
            helper.assertTrue(text.contains("test_hut by TestBuildHut"), "the snapshot does not list the structure:\n" + text);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void buildFailsWithExactMissingList(GameTestHelper helper) {
        forget(helper, "test_short");
        GameTestKit.Spawned s = hutBot(helper, "TestBuildShort", 10);
        run(s.bot(), "build", hutArgs(helper, "test_short"));
        helper.runAfterDelay(20, () -> {
            String events = GameTestKit.events(s.bot());
            boolean placed = false;
            for (int x = 1; x <= 3; x++) for (int y = 1; y <= 3; y++) for (int z = 3; z <= 5; z++) placed |= helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE);
            int held = cobblestone(s.bot());
            GameTestKit.remove(s);
            helper.assertTrue(events.contains("failed: build test_short: missing materials: 13 cobblestone"), events);
            helper.assertTrue(!placed && held == 10, "nothing may be placed when materials are missing");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void buildFailsFastOnUnplaceableLegend(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestBuildWater", 1.5, 1.5);
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        JsonObject args = JsonParser.parseString("{\"name\":\"test_pool\",\"layers\":[[\"ww\",\"ww\"]],\"legend\":{\"w\":\"water\"},"
                + "\"x\":" + at.getX() + ",\"y\":" + at.getY() + ",\"z\":" + at.getZ() + "}").getAsJsonObject();
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("build", args));
        if (r instanceof ActionFactory.Result.Invalid invalid) {
            helper.assertTrue(invalid.reason().contains("can't be placed by hand"), invalid.reason());
            GameTestKit.remove(s);
            helper.succeed();
            return;
        }
        run(s.bot(), "build", args);
        helper.runAfterDelay(5, () -> {
            String events = GameTestKit.events(s.bot());
            boolean anything = !helper.getBlockState(new BlockPos(3, 1, 3)).isAir();
            GameTestKit.remove(s);
            helper.assertTrue(events.contains("can't be placed by hand"), events);
            helper.assertTrue(!anything, "nothing may be placed");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void buildNeverClearsPlayerPlacedBlocks(GameTestHelper helper) {
        forget(helper, "test_blocked");
        GameTestKit.Spawned s = hutBot(helper, "TestBuildPlaced", 30);
        helper.setBlock(2, 1, 4, Blocks.DIRT); // the hut's hollow middle
        PlacedBlocks.of(helper.getLevel().getServer()).record(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 4)), "Steve");
        run(s.bot(), "build", hutArgs(helper, "test_blocked"));
        helper.runAfterDelay(20, () -> {
            String events = GameTestKit.events(s.bot());
            GameTestKit.remove(s);
            helper.assertTrue(events.contains("failed: build test_blocked") && events.contains("placed by Steve"), events);
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 4)).is(Blocks.DIRT), "the placed dirt was cleared");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 1200)
    public void buildClearsNaturalBlocksInFootprint(GameTestHelper helper) {
        forget(helper, "test_cleared");
        GameTestKit.Spawned s = hutBot(helper, "TestBuildNatural", 30);
        helper.setBlock(2, 1, 4, Blocks.DIRT);
        run(s.bot(), "build", hutArgs(helper, "test_cleared"));
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: build test_cleared"), "not finished yet: " + GameTestKit.events(s.bot()));
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 4)).isAir(), "the natural dirt was not cleared");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 4000, padding = 24)
    public void buildsTowerWithScaffold(GameTestHelper helper) {
        forget(helper, "test_tower");
        GameTestKit.clearBarriers(helper, 16);
        GameTestKit.floor(helper, 16, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestBuildTower", 8.5, 4.5);
        // Scaffold first: the navigator spends the first throwaway it finds, and that must not be building material.
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.DIRT, 16));
        s.bot().player.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 58));
        JsonObject args = JsonParser.parseString("{\"template\":\"tower\",\"size\":3,\"material\":\"cobblestone\",\"name\":\"test_tower\",\"facing\":\"S\"}").getAsJsonObject();
        run(s.bot(), "build_template", args);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: build test_tower"), "not finished yet: " + GameTestKit.events(s.bot()));
            int cobble = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 1; y <= 12; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        helper.assertTrue(!helper.getBlockState(p).is(Blocks.DIRT), "scaffolding was left at " + p);
                        if (helper.getBlockState(p).is(Blocks.COBBLESTONE)) cobble++;
                    }
                }
            }
            helper.assertTrue(cobble == 58, "the tower should be 58 cobblestone, found " + cobble);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1200)
    public void buildsATemplateWall(GameTestHelper helper) {
        forget(helper, "test_wall");
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestBuildWall", 4.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 12));
        JsonObject args = JsonParser.parseString("{\"template\":\"wall\",\"size\":4,\"material\":\"cobblestone\",\"name\":\"test_wall\",\"facing\":\"S\"}").getAsJsonObject();
        run(s.bot(), "build_template", args);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: build test_wall"), "not finished yet: " + GameTestKit.events(s.bot()));
            int found = 0;
            for (int x = 0; x < 8; x++) for (int y = 1; y <= 3; y++) for (int z = 0; z < 8; z++) if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBBLESTONE)) found++;
            helper.assertTrue(found == 12, "the wall should be 12 blocks, found " + found);
            GameTestKit.remove(s);
        });
    }
}
