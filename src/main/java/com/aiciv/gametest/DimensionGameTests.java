package com.aiciv.gametest;

import com.aiciv.actions.GoToAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.combat.BowShot;
import com.aiciv.dimension.EnterPortalAction;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.Pos;
import com.aiciv.world.Ballistics;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Buckets;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.MenuOps;
import com.aiciv.world.Riding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.material.Fluids;
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

    // ------------------------------------------------------------------ Part B: shared hands

    @GameTest(maxTicks = 200, padding = 24)
    public void vehicleIsServerDrivenForBots(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 12);
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 11; z++) {
                boolean pool = x >= 2 && x <= 4 && z >= 1 && z <= 10;
                helper.setBlock(x, -1, z, Blocks.STONE);
                helper.setBlock(x, 0, z, pool ? Blocks.WATER : Blocks.STONE);
            }
        }
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 6, 11);
        Boat boat = helper.spawn(EntityTypes.OAK_BOAT, 3.5f, 1.0f, 2.5f);
        boat.setYRot(0); // facing south, down the pool
        int[] paddled = {-1};
        Vec3[] start = new Vec3[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimBoat", 1.5, 2.5, () -> {
            if (paddled[0] >= 0 && paddled[0] < 40) {
                boat.setInput(false, false, true, false);
                paddled[0]++;
            }
        });
        AiPlayer p = s.bot().player;
        helper.runAfterDelay(5, () -> {
            BlockUse.Result r = Riding.mount(p, boat);
            helper.assertTrue(r == BlockUse.Result.USED, "mount: " + r);
            start[0] = boat.position();
            paddled[0] = 0;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(paddled[0] >= 40, "still paddling");
            double moved = boat.position().distanceTo(start[0]);
            helper.assertTrue(moved >= 2, "the boat moved only " + moved);
            Riding.dismount(p);
            helper.assertTrue(!p.isPassenger(), "still riding");
            boat.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 200, padding = 24)
    public void bowShotHitsZombie(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 12);
        for (int x = 0; x < 13; x++) for (int z = 2; z <= 6; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 2, 12, 6);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 11.5f, 1.0f, 4.5f);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)); // no burning in daylight
        BowShot shot = new BowShot(() -> zombie.getBoundingBox().getCenter(), 20);
        BowShot.State[] state = {BowShot.State.DRAWING};
        long[] releasedAt = {-1};
        GameTestKit.Spawned[] holder = new GameTestKit.Spawned[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimBow", 1.5, 4.5, () -> {
            if (state[0] != BowShot.State.DRAWING || holder[0] == null) return;
            state[0] = shot.tick(holder[0].bot().player);
            if (state[0] == BowShot.State.RELEASED) releasedAt[0] = helper.getTick();
        });
        holder[0] = s;
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.BOW));
        p.getInventory().setItem(1, new ItemStack(Items.ARROW, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(state[0] != BowShot.State.FAILED, "the shot failed: " + shot.reason());
            helper.assertTrue(releasedAt[0] >= 0 && releasedAt[0] <= 40, "released at " + releasedAt[0]);
            helper.assertTrue(helper.getTick() <= releasedAt[0] + 60, "the arrow missed");
            helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "the zombie is unhurt");
            int arrows = InventoryOps.count(p, st -> st.is(ItemTags.ARROWS));
            helper.assertTrue(arrows == 2, "arrows left " + arrows);
            zombie.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 200, padding = 24)
    public void thrownSnowballLandsNearTarget(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 16);
        for (int x = 0; x < 16; x++) for (int z = 2; z <= 6; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 2, 15, 6);
        Vec3 target = helper.absoluteVec(new Vec3(13.5, 1.0, 4.5));
        Snowball[] ball = new Snowball[1];
        String[] thrown = {"not yet"};
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimSnow", 1.5, 4.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.SNOWBALL, 4));
        helper.runAfterDelay(3, () -> {
            Optional<float[]> aim = ItemUse.aimThrow(p, Ballistics.Kind.THROWN, target);
            helper.assertTrue(aim.isPresent(), "out of range");
            helper.assertTrue(ItemUse.select(p, st -> st.is(Items.SNOWBALL)), "no snowball");
            thrown[0] = ItemUse.useFacing(p, aim.get()[0], aim.get()[1]) + " from " + p.position() + " at " + aim.get()[0] + "/" + aim.get()[1];
            // useItem adds the snowball to the level at once.
            List<Snowball> seen = p.level().getEntitiesOfClass(Snowball.class, p.getBoundingBox().inflate(3));
            if (!seen.isEmpty()) ball[0] = seen.get(0);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ball[0] != null, "no snowball was thrown: " + thrown[0]);
            // The snowball is removed where it hits, so its last position is the landing point.
            helper.assertTrue(ball[0].isRemoved(), "the snowball is still flying at " + ball[0].position() + "; thrown " + thrown[0]);
            double off = ball[0].position().distanceTo(target);
            helper.assertTrue(off <= 1.5, "the snowball landed " + off + " from the target, at " + ball[0].position());
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 100)
    public void bucketFillsAndPours(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(5, -1, 3, Blocks.STONE);
        helper.setBlock(5, 0, 3, Blocks.WATER);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimBucket", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.BUCKET));
        helper.runAfterDelay(3, () -> {
            BlockUse.Result filled = Buckets.fill(p, helper.absolutePos(new BlockPos(5, 0, 3)));
            helper.assertTrue(filled == BlockUse.Result.USED, "fill: " + filled);
            helper.assertTrue(InventoryOps.has(p, Items.WATER_BUCKET, 1), "no water_bucket");
            BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 3));
            BlockUse.Result poured = Buckets.pour(p, cell, Items.WATER_BUCKET);
            helper.assertTrue(poured == BlockUse.Result.USED, "pour: " + poured);
            helper.assertTrue(helper.getLevel().getFluidState(cell).isSourceOfType(Fluids.WATER), "no water source poured");
            helper.assertTrue(InventoryOps.has(p, Items.BUCKET, 1) && !InventoryOps.has(p, Items.WATER_BUCKET, 1), "the bucket was not emptied");
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    /**
     * The only support of the cell is the top of a pillar the bot's eyes are below: its look ray meets the pillar's side
     * first, and a real bucket would pour beside the pillar, so the pour is refused instead.
     */
    @GameTest(maxTicks = 100)
    public void bucketNeverPoursBesideTheFace(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.STONE);
        helper.setBlock(4, 2, 3, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimFace", 1.5, 3.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
        helper.runAfterDelay(3, () -> {
            BlockPos cell = helper.absolutePos(new BlockPos(4, 3, 3));
            BlockUse.Result poured = Buckets.pour(p, cell, Items.WATER_BUCKET);
            helper.assertTrue(poured == BlockUse.Result.NOT_VISIBLE, "pour: " + poured);
            for (int x = 0; x < 8; x++) {
                for (int y = 1; y <= 4; y++) {
                    for (int z = 0; z < 8; z++) {
                        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(new BlockPos(x, y, z))).isEmpty(),
                                "water was poured at " + x + " " + y + " " + z);
                    }
                }
            }
            helper.assertTrue(InventoryOps.has(p, Items.WATER_BUCKET, 1), "the bucket was emptied");
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void placeAgainstChoosesTheSide(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 2, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimTorch", 4.5, 5.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.TORCH, 4));
        helper.runAfterDelay(3, () -> {
            BlockPlacer.PlaceResult r = BlockPlacer.placeAgainst(p, helper.absolutePos(new BlockPos(4, 1, 3)), Direction.NORTH, st -> st.is(Items.TORCH));
            helper.assertTrue(r == BlockPlacer.PlaceResult.PLACED, "place: " + r);
            BlockState placed = helper.getBlockState(new BlockPos(4, 1, 3));
            helper.assertTrue(placed.is(Blocks.WALL_TORCH) && placed.getValue(WallTorchBlock.FACING) == Direction.SOUTH, "placed " + placed);
            BlockPlacer.PlaceResult none = BlockPlacer.placeAgainst(p, helper.absolutePos(new BlockPos(5, 1, 3)), Direction.NORTH, st -> st.is(Items.TORCH));
            helper.assertTrue(none == BlockPlacer.PlaceResult.NO_SUPPORT, "against air: " + none);
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void putIntoFillsChosenSlot(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.CHEST);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestDimChest", 2.5, 3.5);
        AiPlayer p = s.bot().player;
        p.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        helper.runAfterDelay(3, () -> {
            ChestMenu menu = MenuOps.open(p, helper.absolutePos(new BlockPos(4, 1, 3)), ChestMenu.class);
            helper.assertTrue(menu != null, "the chest did not open");
            int moved = MenuOps.putInto(p, menu, st -> st.is(Items.COBBLESTONE), 5, 3);
            helper.assertTrue(moved == 3, "moved " + moved);
            ItemStack in = menu.getSlot(5).getItem();
            helper.assertTrue(in.is(Items.COBBLESTONE) && in.getCount() == 3, "slot 5 holds " + in);
            int held = InventoryOps.count(p, st -> st.is(Items.COBBLESTONE));
            helper.assertTrue(held == 7, "the bot holds " + held);
            helper.assertTrue(menu.getCarried().isEmpty(), "something is carried");
            int rest = MenuOps.putInto(p, menu, st -> st.is(Items.COBBLESTONE), 6, 64);
            helper.assertTrue(rest == 7 && menu.getSlot(6).getItem().getCount() == 7, "moved the rest: " + rest);
            MenuOps.close(p);
            GameTestKit.remove(s);
            helper.succeed();
        });
    }
}
