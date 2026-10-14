package com.aiciv.gametest;

import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.dimension.Waypoints;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.Pos;
import com.aiciv.stronghold.EyeReadings;
import com.aiciv.stronghold.EyeWatcher;
import com.aiciv.stronghold.DigToStrongholdAction;
import com.aiciv.stronghold.FillEndPortalAction;
import com.aiciv.stronghold.FindPortalRoomAction;
import com.aiciv.stronghold.GoToEndRoutine;
import com.aiciv.stronghold.GoToStrongholdRoutine;
import com.aiciv.stronghold.LocateStrongholdRoutine;
import com.aiciv.stronghold.MakeEyesRoutine;
import com.aiciv.stronghold.ThrowEyeAction;
import com.aiciv.world.InventoryOps;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Task 3: eyes of ender, the stronghold and the End portal. Bot names start with TestHold. */
public final class StrongholdGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("stronghold")), "stronghold module missing");
        helper.succeed();
    }

    @GameTest(maxTicks = 120, padding = 24)
    public void eyeWatcherReadsBearing(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 24);
        GameTestKit.floor(helper, 8, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 24, 8);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldWatch", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        EyeWatcher[] watcher = new EyeWatcher[1];
        long[] startedAt = {-1};
        helper.runAfterDelay(5, () -> {
            ServerLevel level = p.level();
            Vec3 eyes = p.getEyePosition();
            watcher[0] = EyeWatcher.start(p, Set.of());
            EyeOfEnder eye = new EyeOfEnder(level, eyes.x, eyes.y, eyes.z);
            eye.setItem(new ItemStack(Items.ENDER_EYE));
            eye.signalTo(eyes.add(100, 0, 0)); // due east
            level.addFreshEntity(eye);
            startedAt[0] = helper.getTick();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(watcher[0] != null, "the eye is not thrown yet");
            EyeWatcher.State state = watcher[0].tick(p);
            helper.assertTrue(state == EyeWatcher.State.READ, "state " + state);
            helper.assertTrue(helper.getTick() - startedAt[0] <= 40, "read after " + (helper.getTick() - startedAt[0]) + " ticks");
            EyeReadings.Reading r = watcher[0].reading();
            helper.assertTrue(Math.abs(r.bearing() - 90) <= 5, "bearing " + r.bearing());
            helper.assertTrue(!r.sank(), "the eye sank");
            release.run();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 500)
    public void throwEyeWithoutStronghold(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldThrow", 3.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 1));
        bot.executor.replace(List.of(new ThrowEyeAction(true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            boolean read = bot.attachments.find(EyeReadings.class).map(r -> !r.all().isEmpty()).orElse(false);
            helper.assertTrue(events.contains("does not fly") || (read && events.contains("finished: the eye")), "neither failed nor read: " + events);
            helper.assertTrue(!bot.executor.busy(), "still busy: " + bot.executor.currentDescription());
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200)
    public void makesEyes(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(5, 1, 3, Blocks.CRAFTING_TABLE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldMake", 3.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.BLAZE_ROD, 2));
        bot.player.getInventory().setItem(1, new ItemStack(Items.ENDER_PEARL, 3));
        bot.executor.replace(List.of(new MakeEyesRoutine(3)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: made 3 ender_eye"), "not finished: " + events);
            helper.assertTrue(InventoryOps.count(bot.player, st -> st.is(Items.ENDER_EYE)) == 3, "eyes: " + InventoryOps.count(bot.player, st -> st.is(Items.ENDER_EYE)));
            helper.assertTrue(InventoryOps.count(bot.player, st -> st.is(Items.BLAZE_POWDER)) == 1, "powder left: " + InventoryOps.count(bot.player, st -> st.is(Items.BLAZE_POWDER)));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void makeEyesFailsWithoutPearls(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldNoPearl", 3.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.BLAZE_POWDER, 4));
        bot.player.getInventory().setItem(1, new ItemStack(Items.ENDER_PEARL, 1));
        bot.executor.replace(List.of(new MakeEyesRoutine(3)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("needs 2 more ender_pearl"), "unexpected: " + events);
            helper.assertTrue(InventoryOps.count(bot.player, st -> st.is(Items.ENDER_EYE)) == 0, "made eyes anyway");
            GameTestKit.remove(s);
        });
    }

    // ------------------------------------------------------------------ the portal ring

    /** A 3 x 3 hole with a frame ring around it, centred at (8, 1, 8); {@code empty} lists frames left without an eye. */
    private static void ring(GameTestHelper helper, List<BlockPos> empty) {
        GameTestKit.clearBarriers(helper, 16);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        for (int i = 7; i <= 9; i++) {
            frame(helper, i, 6, Direction.SOUTH, empty);
            frame(helper, i, 10, Direction.NORTH, empty);
            frame(helper, 6, i, Direction.EAST, empty);
            frame(helper, 10, i, Direction.WEST, empty);
        }
    }

    private static void frame(GameTestHelper helper, int x, int z, Direction facing, List<BlockPos> empty) {
        boolean eye = !empty.contains(new BlockPos(x, 1, z));
        helper.setBlock(x, 1, z, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(EndPortalFrameBlock.FACING, facing).setValue(EndPortalFrameBlock.HAS_EYE, eye));
    }

    private static long filledFrames(GameTestHelper helper) {
        long n = 0;
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                var state = helper.getBlockState(new BlockPos(x, 1, z));
                if (state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE)) n++;
            }
        }
        return n;
    }

    private static long portalBlocks(GameTestHelper helper) {
        long n = 0;
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) if (helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.END_PORTAL)) n++;
        return n;
    }

    private static final List<BlockPos> THREE_EMPTY = List.of(new BlockPos(8, 1, 6), new BlockPos(6, 1, 8), new BlockPos(10, 1, 9));

    @GameTest(maxTicks = 600, padding = 24)
    public void fillsEndPortal(GameTestHelper helper) {
        ring(helper, THREE_EMPTY);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldFill", 2.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 3));
        bot.executor.replace(List.of(new FillEndPortalAction()), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: filled 3 frames; the end portal is open"), "not finished: " + events);
            helper.assertTrue(portalBlocks(helper) == 9, "portal blocks: " + portalBlocks(helper));
            helper.assertTrue(InventoryOps.count(bot.player, st -> st.is(Items.ENDER_EYE)) == 0, "eyes left");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100, padding = 24)
    public void fillEndPortalFailsWithTooFewEyes(GameTestHelper helper) {
        ring(helper, THREE_EMPTY);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldFew", 2.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 1));
        bot.executor.replace(List.of(new FillEndPortalAction()), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("needs 3 ender_eye for 3 empty frames (you have 1)"), "unexpected: " + events);
            helper.assertTrue(filledFrames(helper) == 9, "filled frames " + filledFrames(helper));
            helper.assertTrue(InventoryOps.count(bot.player, st -> st.is(Items.ENDER_EYE)) == 1, "the eye was used");
            GameTestKit.remove(s);
        });
    }

    // ------------------------------------------------------------------ the portal room

    /** A brick block x 0..20, z 5..11, y 0..4 with a corridor (x 1..14) into a room (x 15..19). */
    private static void corridorAndRoom(GameTestHelper helper, boolean withFrames, boolean withSpawner) {
        GameTestKit.clearBarriers(helper, 24);
        for (int x = 0; x <= 20; x++) {
            for (int z = 5; z <= 11; z++) for (int y = 0; y <= 4; y++) helper.setBlock(x, y, z, Blocks.STONE_BRICKS);
        }
        for (int x = 1; x <= 14; x++) for (int z = 7; z <= 9; z++) for (int y = 1; y <= 3; y++) helper.setBlock(x, y, z, Blocks.AIR);
        for (int x = 15; x <= 19; x++) for (int z = 6; z <= 10; z++) for (int y = 1; y <= 3; y++) helper.setBlock(x, y, z, Blocks.AIR);
        if (withFrames) {
            for (int z = 6; z <= 9; z++) {
                helper.setBlock(18, 1, z, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, Direction.WEST));
            }
        }
        if (withSpawner) {
            helper.setBlock(17, 1, 10, Blocks.SPAWNER);
            helper.getBlockEntity(new BlockPos(17, 1, 10), SpawnerBlockEntity.class).setEntityId(EntityTypes.SILVERFISH, helper.getLevel().getRandom());
        }
    }

    @GameTest(maxTicks = 1300, padding = 24)
    public void findsPortalRoomDownCorridor(GameTestHelper helper) {
        corridorAndRoom(helper, true, true);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldRoom", 1.5, 8.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new FindPortalRoomAction(64, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: found the end portal room at"), "not finished: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow(() -> new IllegalStateException("no waypoints"));
            BlockPos bp = bot.player.blockPosition();
            helper.assertTrue(waypoints.nearest(Waypoints.END_PORTAL, bot.dimension, new Pos(bp.getX(), bp.getY(), bp.getZ())).isPresent(),
                    "no end_portal waypoint");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1300, padding = 24)
    public void aSilverfishSpawnerAloneMarksThePortalRoom(GameTestHelper helper) {
        corridorAndRoom(helper, false, true);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldSpawn", 1.5, 8.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new FindPortalRoomAction(64, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: found the end portal room at"), "not finished: " + events);
            Waypoints waypoints = bot.attachments.find(Waypoints.class).orElseThrow(() -> new IllegalStateException("no waypoints"));
            helper.assertTrue(!waypoints.in(bot.dimension).isEmpty(), "no waypoint");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1300, padding = 24)
    public void findPortalRoomGivesUpWhenThereIsNone(GameTestHelper helper) {
        corridorAndRoom(helper, false, false);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldNone", 1.5, 8.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new FindPortalRoomAction(64, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("explored the stronghold nearby and found no portal room"),
                    "unexpected: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1800, padding = 24)
    public void digsDownToStrongholdBricks(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                for (int y = -14; y <= 0; y++) helper.setBlock(x, y, z, Blocks.STONE);
                // a small brick room 9 to 12 blocks down
                for (int y = -12; y <= -9; y++) {
                    boolean shell = x == 0 || x == 7 || z == 0 || z == 7 || y == -12 || y == -9;
                    helper.setBlock(x, y, z, shell ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
            }
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldDig", 3.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.player.getInventory().setItem(1, new ItemStack(Items.IRON_SHOVEL));
        bot.executor.replace(List.of(new DigToStrongholdAction(-50, true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("finished: reached the stronghold at"), "not there yet: " + events + " at " + bot.player.blockPosition());
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1800, padding = 24)
    public void goToEndFillsTheRingAndEntersIt(GameTestHelper helper) {
        ring(helper, THREE_EMPTY);
        boolean hasEnd = helper.getLevel().getServer().getLevel(Level.END) != null;
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldEnd", 2.5, 3.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.ENDER_EYE, 3));
        bot.executor.replace(List.of(new GoToEndRoutine(true)), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            if (!hasEnd) {
                helper.assertTrue(events.contains("the_end does not exist on this server"), "unexpected: " + events);
            } else {
                helper.assertTrue(events.contains("finished: went through the end portal to the_end"), "not in the End: " + events);
                helper.assertTrue(bot.player.level().dimension() == Level.END, "the bot is in " + bot.player.level().dimension());
            }
            helper.assertTrue(portalBlocks(helper) == 9, "portal blocks: " + portalBlocks(helper));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void goToStrongholdNeedsAnEstimate(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldNoEst", 3.5, 3.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new GoToStrongholdRoutine(true)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(bot).contains("no stronghold estimate; use locate_stronghold first"), "unexpected: " + GameTestKit.events(bot));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void locateStrongholdNeedsEyes(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestHoldNoEye", 3.5, 3.5);
        Bot bot = s.bot();
        bot.executor.replace(List.of(new LocateStrongholdRoutine(96, true)), bot.context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(bot).contains("needs 1 ender_eye (you have 0)"), "unexpected: " + GameTestKit.events(bot));
            GameTestKit.remove(s);
        });
    }
}
