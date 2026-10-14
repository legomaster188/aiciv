package com.aiciv.gametest;

import com.aiciv.actions.GoToAction;
import com.aiciv.bot.Bot;
import com.aiciv.dimension.EnterPortalAction;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Task 1: dimensions, portals and the shared hands of milestone 4. Bot names start with TestDim. */
public final class DimensionGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("dimension")), "dimension module missing");
        helper.succeed();
    }

    private static BlockState portal(Direction.Axis axis) {
        return Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
    }

    private static double distance(Pos a, BlockPos b) {
        double dx = a.x() - b.getX(), dy = a.y() - b.getY(), dz = a.z() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @GameTest(maxTicks = 800, padding = 24)
    public void entersNetherPortalAndArrives(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int x = 3; x <= 6; x++) {
            helper.setBlock(x, 1, 6, Blocks.OBSIDIAN);
            helper.setBlock(x, 5, 6, Blocks.OBSIDIAN);
        }
        for (int y = 2; y <= 4; y++) {
            helper.setBlock(3, y, 6, Blocks.OBSIDIAN);
            helper.setBlock(6, y, 6, Blocks.OBSIDIAN);
        }
        for (int y = 2; y <= 4; y++) for (int x = 4; x <= 5; x++) helper.setBlock(x, y, 6, portal(Direction.Axis.X));
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        long[] doneAt = {-1};
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimPortal", 4.5, 2.5, () -> {
            if (doneAt[0] < 0 && holder[0] != null && !holder[0].bot().executor.busy()) doneAt[0] = helper.getTick();
        });
        holder[0] = s;
        Bot bot = s.bot();
        bot.executor.replace(List.of(new EnterPortalAction("nether")), bot.context);
        BlockPos frameCentre = helper.absolutePos(new BlockPos(4, 2, 6));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            if (nether == null) {
                helper.assertTrue(events.contains("the_nether does not exist on this server"), "unexpected: " + events);
                GameTestKit.remove(s);
                return;
            }
            helper.assertTrue(events.contains("finished: went through to the_nether; you are at"), "not through yet: " + events);
            helper.assertTrue(doneAt[0] >= 0 && doneAt[0] <= 600, "took until tick " + doneAt[0]);
            helper.assertTrue(bot.player.level().dimension() == Level.NETHER, "the bot is in " + Portals.key(bot.player.level()));
            helper.assertTrue(events.contains("you are now in the_nether"), "no dimension event: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow();
            Waypoints.Waypoint home = waypoints.nearest(Waypoints.PORTAL, "minecraft:overworld", new Pos(frameCentre.getX(), frameCentre.getY(), frameCentre.getZ()))
                    .orElseThrow(() -> new IllegalStateException("no overworld portal waypoint"));
            helper.assertTrue(distance(home.pos(), frameCentre) <= 3, "overworld portal waypoint at " + home.pos());
            helper.assertTrue(home.note().equals("to the_nether"), "note " + home.note());
            helper.assertTrue(!waypoints.in("minecraft:the_nether").isEmpty()
                    && waypoints.in("minecraft:the_nether").get(0).kind().equals(Waypoints.PORTAL), "nether waypoints " + waypoints.in("minecraft:the_nether"));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200, padding = 24)
    public void actionFailsWhenBotChangesDimension(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server has no nether");
        GameTestKit.clearBarriers(helper, 8);
        for (int x = 0; x < 24; x++) for (int z = 0; z < 3; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        BlockPos o = new BlockPos(4100, 100, 4000);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                nether.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) nether.setBlockAndUpdate(o.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        long[] idleAfter = {-1};
        long[] movedAt = {-1};
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimGoTo", 1.5, 1.5, () -> {
            if (movedAt[0] >= 0 && idleAfter[0] < 0 && !holder[0].bot().executor.busy()) idleAfter[0] = helper.getTick() - movedAt[0];
        });
        holder[0] = s;
        Bot bot = s.bot();
        BlockPos far = helper.absolutePos(new BlockPos(21, 1, 1));
        bot.executor.replace(List.of(new GoToAction(far.getX(), far.getY(), far.getZ())), bot.context);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(bot.executor.busy(), "the goTo ended before the move: " + GameTestKit.events(bot));
            movedAt[0] = helper.getTick();
            // Test harness only: actions never teleport.
            bot.player.teleport(new TeleportTransition(nether, Vec3.atBottomCenterOf(o), Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(idleAfter[0] >= 0, "still busy: " + bot.executor.currentDescription());
            helper.assertTrue(idleAfter[0] <= 2, "the executor took " + idleAfter[0] + " ticks to stop");
            helper.assertTrue(events.contains("you went through to the_nether"), "no failure: " + events);
            helper.assertTrue(bot.navigator.status().state() != NavStatus.State.RUNNING, "the navigator still runs");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void navigationNeverEntersPortal(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 12);
        // A corridor along x: lanes z = 1 (left) and z = 2, walls at z = 0 and z = 3, three blocks high.
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z <= 3; z++) helper.setBlock(x, 0, z, Blocks.STONE);
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(x, y, 0, Blocks.STONE);
                helper.setBlock(x, y, 3, Blocks.STONE);
            }
        }
        // The left lane runs straight through a portal at x = 5 (framed above, below and on the wall side).
        helper.setBlock(5, 0, 1, Blocks.OBSIDIAN);
        helper.setBlock(5, 3, 1, Blocks.OBSIDIAN);
        helper.setBlock(5, 1, 0, Blocks.OBSIDIAN);
        helper.setBlock(5, 2, 0, Blocks.OBSIDIAN);
        helper.setBlock(5, 1, 1, portal(Direction.Axis.Z));
        helper.setBlock(5, 2, 1, portal(Direction.Axis.Z));
        List<String> problems = new ArrayList<>();
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimCorridor", 1.5, 1.5, () -> {
            Bot b = holder[0].bot();
            if (!b.player.level().dimension().equals(Level.OVERWORLD)) problems.add("changed dimension");
            BlockPos feet = b.player.blockPosition();
            if (Portals.isPortal(b.player.level().getBlockState(feet)) || Portals.isPortal(b.player.level().getBlockState(feet.above()))) {
                problems.add("stood in the portal at " + feet);
            }
        });
        holder[0] = s;
        Bot bot = s.bot();
        helper.assertTrue(helper.getBlockState(new BlockPos(5, 1, 1)).is(Blocks.NETHER_PORTAL), "the portal did not stay");
        BlockPos end = helper.absolutePos(new BlockPos(10, 1, 1));
        bot.executor.replace(List.of(new GoToAction(end.getX(), end.getY(), end.getZ())), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(problems.isEmpty(), "problems: " + problems);
            helper.assertTrue(events.contains("finished: goTo"), "not arrived: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 40)
    public void aiPlayerHasSeenCredits(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimCredits", 3.5, 3.5);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(s.bot().player.seenCredits, "seenCredits is not set");
            GameTestKit.remove(s);
            helper.succeed();
        });
    }
}
