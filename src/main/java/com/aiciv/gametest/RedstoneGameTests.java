package com.aiciv.gametest;

import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.Bot;
import com.aiciv.building.StructureRegistry;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.utility.UseBlockAction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Task 10: redstone blueprints and use_block. Bot names start with TestRed. */
public final class RedstoneGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("building")), "building module missing");
        helper.succeed();
    }

    private static void build(GameTestHelper helper, Bot bot, String name, String layers, String legend, BlockPos corner, String facing) {
        BlockPos at = helper.absolutePos(corner);
        JsonObject args = JsonParser.parseString("{\"name\":\"" + name + "\",\"layers\":" + layers + ",\"legend\":" + legend
                + ",\"x\":" + at.getX() + ",\"y\":" + at.getY() + ",\"z\":" + at.getZ() + ",\"facing\":\"" + facing + "\"}").getAsJsonObject();
        ActionFactory.Result r = ActionFactory.create(ToolCall.of("build", args));
        if (r instanceof ActionFactory.Result.Ok ok) bot.executor.replace(List.of(ok.action()), bot.context);
        else throw new IllegalStateException("build was refused: " + ((ActionFactory.Result.Invalid) r).reason());
    }

    private static void forget(GameTestHelper helper, String structure) {
        StructureRegistry.of(helper.getLevel().getServer()).store().remove(structure);
    }

    @GameTest(maxTicks = 1200)
    public void buildsRepeaterLineFacingNorth(GameTestHelper helper) {
        forget(helper, "test_red_line");
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRedLine", 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.REDSTONE, 2));
        s.bot().player.getInventory().setItem(1, new ItemStack(Items.REPEATER, 1));
        build(helper, s.bot(), "test_red_line", "[[\"d\",\"R\",\"d\"]]", "{\"d\":\"redstone_wire\",\"R\":\"repeater[facing=north,delay=3]\"}",
                new BlockPos(3, 1, 2), "S");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: build test_red_line"), "not finished yet: " + events);
            var repeater = helper.getBlockState(new BlockPos(3, 1, 3));
            helper.assertTrue(repeater.is(Blocks.REPEATER), "no repeater: " + repeater);
            helper.assertTrue(repeater.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "repeater faces " + repeater.getValue(BlockStateProperties.HORIZONTAL_FACING));
            helper.assertTrue(repeater.getValue(BlockStateProperties.DELAY) == 3, "repeater delay is " + repeater.getValue(BlockStateProperties.DELAY));
            helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 2)).is(Blocks.REDSTONE_WIRE), "no wire at the start");
            helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 4)).is(Blocks.REDSTONE_WIRE), "no wire at the end");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1200)
    public void buildsPistonFacingUp(GameTestHelper helper) {
        forget(helper, "test_red_piston");
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRedPiston", 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.PISTON, 1));
        build(helper, s.bot(), "test_red_piston", "[[\"P\"]]", "{\"P\":\"piston[facing=up]\"}", new BlockPos(3, 1, 3), "S");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: build test_red_piston"), "not finished yet: " + events);
            var piston = helper.getBlockState(new BlockPos(3, 1, 3));
            helper.assertTrue(piston.is(Blocks.PISTON), "no piston: " + piston);
            helper.assertTrue(piston.getValue(BlockStateProperties.FACING) == Direction.UP, "piston faces " + piston.getValue(BlockStateProperties.FACING));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1200)
    public void buildsObserverFacingRelative(GameTestHelper helper) {
        forget(helper, "test_red_observer");
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRedObserv", 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.OBSERVER, 1));
        build(helper, s.bot(), "test_red_observer", "[[\"O\"]]", "{\"O\":\"observer[facing=forward]\"}", new BlockPos(3, 1, 3), "E");
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: build test_red_observer"), "not finished yet: " + events);
            var observer = helper.getBlockState(new BlockPos(3, 1, 3));
            helper.assertTrue(observer.is(Blocks.OBSERVER), "no observer: " + observer);
            helper.assertTrue(observer.getValue(BlockStateProperties.FACING) == Direction.EAST, "observer faces " + observer.getValue(BlockStateProperties.FACING));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200)
    public void orientationFailsWhenNoStandingSpot(GameTestHelper helper) {
        forget(helper, "test_red_boxed");
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                for (int y = 1; y <= 3; y++) helper.setBlock(x, y, z, Blocks.STONE);
            }
        }
        helper.setBlock(3, 1, 3, Blocks.AIR);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRedBoxed", 1.5, 1.5);
        // On top of the stone, out of the box's hollow: nothing within reach of the cell is a place to stand.
        Vec3 top = helper.absoluteVec(new Vec3(1.5, 4, 1.5));
        s.bot().player.teleportTo(top.x, top.y, top.z);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.REPEATER, 1));
        build(helper, s.bot(), "test_red_boxed", "[[\"R\"]]", "{\"R\":\"repeater[facing=north]\"}", new BlockPos(3, 1, 3), "S");
        helper.runAfterDelay(40, () -> {
            String events = GameTestKit.events(s.bot());
            boolean placed = !helper.getBlockState(new BlockPos(3, 1, 3)).isAir();
            int held = s.bot().player.getInventory().countItem(Items.REPEATER);
            GameTestKit.remove(s);
            helper.assertTrue(events.contains("can't place repeater"), events);
            helper.assertTrue(!placed && held == 1, "nothing may be placed (placed=" + placed + ", repeaters held=" + held + ")");
            helper.succeed();
        });
    }

    private static void failsWith(GameTestHelper helper, String botName, String name, String legendValue, String expected) {
        forget(helper, name);
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, botName, 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.REPEATER, 1));
        build(helper, s.bot(), name, "[[\"R\"]]", "{\"R\":\"" + legendValue + "\"}", new BlockPos(3, 1, 3), "S");
        helper.runAfterDelay(10, () -> {
            String events = GameTestKit.events(s.bot());
            boolean placed = !helper.getBlockState(new BlockPos(3, 1, 3)).isAir();
            GameTestKit.remove(s);
            helper.assertTrue(events.contains(expected), events);
            helper.assertTrue(!placed, "nothing may be placed");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void refusesStatesThatCannotBeChosenByHand(GameTestHelper helper) {
        failsWith(helper, "TestRedHand", "test_red_hand", "repeater[locked=true]",
                "repeater: property \"locked\" can't be chosen by hand (supported: facing, delay, mode, face, axis, powered)");
    }

    @GameTest(maxTicks = 100)
    public void refusesValuesTheBlockRejects(GameTestHelper helper) {
        failsWith(helper, "TestRedValue", "test_red_value", "repeater[delay=7]", "repeater has no delay=7");
    }

    @GameTest(maxTicks = 400)
    public void useBlockSetsRepeaterDelay(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos repeater = new BlockPos(3, 1, 3);
        helper.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRedUse", 1.5, 1.5);
        BlockPos at = helper.absolutePos(repeater);
        s.bot().executor.replace(List.of(new UseBlockAction(at.getX(), at.getY(), at.getZ()), new UseBlockAction(at.getX(), at.getY(), at.getZ())), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(helper.getBlockState(repeater).getValue(BlockStateProperties.DELAY) == 3, "delay is "
                    + helper.getBlockState(repeater).getValue(BlockStateProperties.DELAY) + ": " + events);
            helper.assertTrue(events.contains("set repeater at") && events.contains("to delay 3"), "unexpected summary: " + events);
            GameTestKit.remove(s);
        });
    }
}
