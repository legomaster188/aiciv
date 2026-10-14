package com.aiciv.gametest;

import static com.aiciv.gametest.GameTestKit.decisionLines;
import static com.aiciv.gametest.GameTestKit.turn;

import com.aiciv.bot.Bot;
import com.aiciv.bot.BotManager;
import com.aiciv.dimension.Waypoints;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.Pos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;

/** Task 11: the whole endgame, played by scripted models. Bot names start with TestEnd. */
public final class EndgameGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        List<String> names = ToolModules.ALL.stream().map(ToolModule::name).toList();
        for (String name : List.of("dimension", "nether", "stronghold", "dragon", "vehicles", "flight", "items", "animals", "stations")) {
            helper.assertTrue(names.contains(name), name + " module missing");
        }
        helper.succeed();
    }

    /** A scripted bot builds and lights a nether portal, goes through, and comes back through the portal it arrived by. */
    @GameTest(maxTicks = 6000, padding = 24)
    public void scriptedBotTravelsToTheNetherAndBack(GameTestHelper helper) throws Exception {
        boolean hasNether = helper.getLevel().getServer().getLevel(Level.NETHER) != null;
        GameTestKit.clearBarriers(helper, 16);
        GameTestKit.floor(helper, 16, Blocks.STONE);
        BlockPos portal = helper.absolutePos(new BlockPos(6, 1, 8));
        ScriptedBackend backend = new ScriptedBackend(List.of(
                turn("build_portal", "{\"x\":" + portal.getX() + ",\"y\":" + portal.getY() + ",\"z\":" + portal.getZ() + ",\"facing\":\"S\"}"),
                turn("enter_portal", "{\"kind\":\"nether\"}"),
                turn("leave_nether", "{}")));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = AiCivGameTests.scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestEndNether", "scripted", "An adventurer.", helper.getLevel(), helper.absoluteVec(new Vec3(8.5, 1, 3.5)));
        Inventory inv = bot.player.getInventory();
        inv.setItem(0, new ItemStack(Items.OBSIDIAN, 14));
        inv.setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
        inv.setItem(2, new ItemStack(Items.COBBLESTONE, 16));
        boolean[] wasInNether = new boolean[1];
        int[] tick = new int[1];
        helper.onEachTick(() -> {
            tick[0]++;
            manager.tickStart();
            manager.tickEnd();
            wasInNether[0] |= bot.player.level().dimension() == Level.NETHER;
        });
        helper.runAfterDelay(5999, () -> {
            if (manager.bot("TestEndNether").isPresent()) AiCivGameTests.removeBot(manager, "TestEndNether");
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            if (!hasNether) {
                helper.assertTrue(events.contains("the_nether does not exist on this server"), "unexpected: " + events);
                AiCivGameTests.removeBot(manager, "TestEndNether");
                return;
            }
            helper.assertTrue(wasInNether[0], "the bot never reached the nether: " + events);
            helper.assertTrue(bot.player.level().dimension() == Level.OVERWORLD,
                    "the bot is still in " + bot.player.level().dimension() + ": " + events);
            double d = Math.sqrt(bot.player.blockPosition().distSqr(portal));
            helper.assertTrue(d <= 6, "the bot is " + d + " blocks from the portal: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow(() -> new IllegalStateException("no waypoints"));
            Waypoints.Waypoint home = waypoints.nearest(Waypoints.PORTAL, "minecraft:overworld", new Pos(portal.getX(), portal.getY(), portal.getZ()))
                    .orElseThrow(() -> new IllegalStateException("no overworld portal waypoint: " + events));
            helper.assertTrue(home.note().equals("to the_nether"), "overworld portal note " + home.note());
            List<Waypoints.Waypoint> there = waypoints.in("minecraft:the_nether");
            helper.assertTrue(there.stream().anyMatch(w -> w.kind().equals(Waypoints.PORTAL) && w.note().equals("to overworld")),
                    "nether waypoints " + there);
            List<String> decisions = decisionLines(dir, "TestEndNether");
            helper.assertTrue(decisions.size() >= 3, "only " + decisions.size() + " decisions logged");
            for (String line : decisions) helper.assertTrue(!line.contains("\"error\""), "a decision logged an error: " + line);
            System.out.println("[EndgameGameTests] scriptedBotTravelsToTheNetherAndBack finished at tick " + tick[0]);
            AiCivGameTests.removeBot(manager, "TestEndNether");
        });
    }

    private static void frame(GameTestHelper helper, int x, int z, Direction facing, boolean eye) {
        helper.setBlock(x, 1, z, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(EndPortalFrameBlock.FACING, facing).setValue(EndPortalFrameBlock.HAS_EYE, eye));
    }

    /** A scripted bot fills the two empty frames of an End portal ring with its eyes and steps through. */
    @GameTest(maxTicks = 3000, padding = 24)
    public void scriptedBotOpensTheEndPortal(GameTestHelper helper) throws Exception {
        boolean hasEnd = helper.getLevel().getServer().getLevel(Level.END) != null;
        GameTestKit.clearBarriers(helper, 16);
        GameTestKit.floor(helper, 16, Blocks.STONE);
        // 12 frames, 10 with an eye: (8, 6) and (6, 8) are empty
        for (int i = 7; i <= 9; i++) {
            frame(helper, i, 6, Direction.SOUTH, i != 8);
            frame(helper, i, 10, Direction.NORTH, true);
            frame(helper, 6, i, Direction.EAST, i != 8);
            frame(helper, 10, i, Direction.WEST, true);
        }
        ScriptedBackend backend = new ScriptedBackend(List.of(
                turn("fill_end_portal", "{}"),
                turn("enter_portal", "{\"kind\":\"end\"}")));
        Path dir = Files.createTempDirectory("aiciv-gametest");
        BotManager manager = AiCivGameTests.scriptedManager(helper, backend, dir);
        Bot bot = manager.spawn("TestEndPortal", "scripted", "An adventurer.", helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 1, 3.5)));
        bot.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 2));
        boolean[] wasInEnd = new boolean[1];
        int[] tick = new int[1];
        helper.onEachTick(() -> {
            tick[0]++;
            manager.tickStart();
            manager.tickEnd();
            wasInEnd[0] |= bot.player.level().dimension() == Level.END;
        });
        helper.runAfterDelay(2999, () -> {
            if (manager.bot("TestEndPortal").isPresent()) AiCivGameTests.removeBot(manager, "TestEndPortal");
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            if (!hasEnd) {
                helper.assertTrue(events.contains("the_end does not exist on this server"), "unexpected: " + events);
                AiCivGameTests.removeBot(manager, "TestEndPortal");
                return;
            }
            helper.assertTrue(wasInEnd[0], "the bot never reached the end: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow(() -> new IllegalStateException("no waypoints"));
            helper.assertTrue(waypoints.in("minecraft:the_end").stream().anyMatch(w -> w.kind().equals(Waypoints.ARRIVAL)),
                    "end waypoints " + waypoints.in("minecraft:the_end") + ": " + events);
            List<String> decisions = decisionLines(dir, "TestEndPortal");
            helper.assertTrue(decisions.size() >= 2, "only " + decisions.size() + " decisions logged");
            for (String line : decisions) helper.assertTrue(!line.contains("\"error\""), "a decision logged an error: " + line);
            System.out.println("[EndgameGameTests] scriptedBotOpensTheEndPortal finished at tick " + tick[0]);
            AiCivGameTests.removeBot(manager, "TestEndPortal");
        });
    }
}
