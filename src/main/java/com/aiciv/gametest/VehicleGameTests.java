package com.aiciv.gametest;

import com.aiciv.actions.Action;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.modules.ToolModules;
import com.aiciv.vehicles.BoardAction;
import com.aiciv.vehicles.BoatToRoutine;
import com.aiciv.vehicles.RideMinecartRoutine;
import com.aiciv.vehicles.RideToAction;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.Riding;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;

/** Task 5: boats, minecarts and mounts. Bot names start with TestRide. */
public final class VehicleGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("vehicles")), "vehicles module missing");
        helper.succeed();
    }

    /** Stone over relative x 0..x1, z 0..z1 at y 0 with a stone layer under it. */
    private static void ground(GameTestHelper helper, int x1, int z1) {
        GameTestKit.clearBarriers(helper, 8);
        for (int x = 0; x <= x1; x++) {
            for (int z = 0; z <= z1; z++) {
                helper.setBlock(x, -1, z, Blocks.STONE);
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static Horse saddledHorse(GameTestHelper helper, float x, float z) {
        Horse horse = helper.spawnWithNoFreeWill(EntityTypes.HORSE, x, 1.0f, z);
        horse.setTamed(true);
        horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        return horse;
    }

    private static void run(Bot bot, Action... actions) {
        bot.executor.replace(List.of(actions), bot.context);
    }

    @GameTest(maxTicks = 2400, padding = 24)
    public void boatToCrossesWater(GameTestHelper helper) {
        ground(helper, 23, 5);
        for (int x = 4; x <= 21; x++) for (int z = 1; z <= 3; z++) helper.setBlock(x, 0, z, Blocks.WATER);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRideBoat", 2.5, 2.5);
        Bot bot = s.bot();
        AiPlayer p = bot.player;
        p.getInventory().setItem(0, new ItemStack(Items.OAK_BOAT));
        BlockPos target = helper.absolutePos(new BlockPos(21, 1, 2));
        run(bot, new BoatToRoutine(target.getX(), target.getZ()));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(!bot.executor.busy(), "still busy: " + bot.executor.currentDescription());
            helper.assertTrue(events.contains("finished: crossed to"), "not finished: " + events);
            double d = Math.hypot(p.getX() - (target.getX() + 0.5), p.getZ() - (target.getZ() + 0.5));
            helper.assertTrue(d <= 3.0, "the bot ended " + d + " blocks from the target");
            helper.assertTrue(!p.isPassenger(), "still riding");
            helper.assertTrue(InventoryOps.has(p, Items.OAK_BOAT, 1), "no boat in the inventory: " + events);
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 400, padding = 24)
    public void boatStuckOnLandFails(GameTestHelper helper) {
        ground(helper, 23, 7);
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) helper.setBlock(x, 0, z, Blocks.WATER);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 7);
        Boat boat = helper.spawn(EntityTypes.OAK_BOAT, 3.5f, 1.0f, 3.5f);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRideStuck", 1.5, 3.5);
        Bot bot = s.bot();
        BlockPos far = helper.absolutePos(new BlockPos(23, 1, 3));
        run(bot, new BoardAction("boat"), new RideToAction(far.getX(), far.getZ()));
        long[] failedAt = {-1};
        helper.onEachTick(() -> {
            if (failedAt[0] < 0 && GameTestKit.events(bot).contains("failed:")) failedAt[0] = helper.getTick();
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(failedAt[0] >= 0, "has not failed: " + events);
            helper.assertTrue(events.contains("stuck"), "no 'stuck' in: " + events);
            helper.assertTrue(failedAt[0] <= 260, "failed only at tick " + failedAt[0]);
            helper.assertTrue(helper.getTick() >= failedAt[0] + 3, "give the boat a tick to stop paddling");
            helper.assertTrue(!boat.getPaddleState(0) && !boat.getPaddleState(1), "the boat is still paddling");
            boat.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 1200, padding = 24)
    public void ridesSaddledHorse(GameTestHelper helper) {
        ground(helper, 23, 5);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        Horse horse = saddledHorse(helper, 3.5f, 2.5f);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRideHorse", 1.5, 2.5);
        Bot bot = s.bot();
        double startX = horse.getX();
        BlockPos target = helper.absolutePos(new BlockPos(17, 1, 2));
        run(bot, new BoardAction("horse"), new RideToAction(target.getX(), target.getZ()));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(!bot.executor.busy(), "still busy: " + bot.executor.currentDescription());
            helper.assertTrue(events.contains("rode the horse to"), "not finished: " + events);
            helper.assertTrue(horse.getX() - startX >= 10, "the horse moved only " + (horse.getX() - startX));
            helper.assertTrue(bot.player.getVehicle() == horse, "no longer riding the horse");
            Riding.dismount(bot.player);
            horse.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 200, padding = 24)
    public void boardPigFailsWithoutSaddle(GameTestHelper helper) {
        ground(helper, 7, 7);
        Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, 3.5f, 1.0f, 3.5f);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRidePig", 1.5, 1.5);
        Bot bot = s.bot();
        run(bot, new BoardAction("pig"));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("failed:") && events.contains("needs a saddle"), "unexpected: " + events);
            helper.assertTrue(!bot.player.isPassenger(), "the bot got on an unsaddled pig");
            pig.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 900, padding = 24)
    public void ridesSaddledPigWithStick(GameTestHelper helper) {
        ground(helper, 23, 5);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, 3.5f, 1.0f, 2.5f);
        pig.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRidePigGo", 1.5, 2.5);
        Bot bot = s.bot();
        bot.player.getInventory().setItem(0, new ItemStack(Items.CARROT_ON_A_STICK));
        double startX = pig.getX();
        BlockPos target = helper.absolutePos(new BlockPos(14, 1, 2));
        run(bot, new BoardAction("pig"), new RideToAction(target.getX(), target.getZ()));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("rode the pig to"), "not finished: " + events + " pig moved " + (pig.getX() - startX));
            helper.assertTrue(pig.getX() - startX >= 8, "the pig moved only " + (pig.getX() - startX));
            Riding.dismount(bot.player);
            pig.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 600, padding = 24)
    public void cancelWhileRidingDismounts(GameTestHelper helper) {
        ground(helper, 23, 5);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        Horse horse = saddledHorse(helper, 3.5f, 2.5f);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRideCancel", 1.5, 2.5);
        Bot bot = s.bot();
        BlockPos target = helper.absolutePos(new BlockPos(22, 1, 2));
        run(bot, new BoardAction("horse"), new RideToAction(target.getX(), target.getZ()));
        double startX = horse.getX();
        long[] stoppedAt = {-1};
        helper.onEachTick(() -> {
            if (stoppedAt[0] < 0 && bot.player.isPassenger() && bot.player.zza != 0 && horse.getX() - startX > 5) {
                bot.executor.stop(bot.context);
                stoppedAt[0] = helper.getTick();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stoppedAt[0] >= 0, "never rode");
            helper.assertTrue(helper.getTick() > stoppedAt[0], "wait one tick");
            helper.assertTrue(!bot.player.isPassenger(), "still riding after the stop");
            helper.assertTrue(bot.player.zza == 0, "still holding forward: " + bot.player.zza);
            horse.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    /**
     * Rides the horse 14 blocks east; {@code whileRiding} runs every tick once the ride is under way (the horse 3 blocks
     * along). Fails if the bot ever gets off early.
     */
    private static void rideWith(GameTestHelper helper, String name, java.util.function.BiConsumer<Bot, Horse> whileRiding,
                                 java.util.function.BiConsumer<Bot, String> check) {
        ground(helper, 23, 5);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        Horse horse = saddledHorse(helper, 3.5f, 2.5f);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 1.5, 2.5);
        Bot bot = s.bot();
        BlockPos target = helper.absolutePos(new BlockPos(17, 1, 2));
        run(bot, new BoardAction("horse"), new RideToAction(target.getX(), target.getZ()));
        boolean[] mounted = {false};
        String[] fellOff = {null};
        double startX = horse.getX();
        helper.onEachTick(() -> {
            if (bot.player.getVehicle() == horse) {
                mounted[0] = true;
                if (bot.executor.upNext(RideToAction.class) && horse.getX() - startX > 3) whileRiding.accept(bot, horse);
            } else if (mounted[0] && fellOff[0] == null && !GameTestKit.events(bot).contains("rode the horse to")) {
                fellOff[0] = "got off at tick " + helper.getTick() + ": " + GameTestKit.events(bot);
            }
        });
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(fellOff[0] == null, String.valueOf(fellOff[0]));
            helper.assertTrue(events.contains("finished:") && events.contains("rode the horse to"), "not finished: " + events);
            helper.assertTrue(bot.player.getVehicle() == horse, "no longer riding the horse: " + events);
            check.accept(bot, events);
            Riding.dismount(bot.player);
            horse.discard();
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 1200, padding = 24)
    public void eatsInTheSaddleWithoutGettingOff(GameTestHelper helper) {
        boolean[] hungry = {false};
        rideWith(helper, "TestRideEat", (bot, horse) -> {
            if (hungry[0]) return;
            hungry[0] = true;
            bot.player.getInventory().setItem(5, new ItemStack(Items.BREAD, 4));
            bot.player.getFoodData().setFoodLevel(10);
        }, (bot, events) -> helper.assertTrue(events.contains("reflex: ate bread"), "never ate: " + events));
    }

    @GameTest(maxTicks = 1200, padding = 24)
    public void hitWhileRidingStaysMounted(GameTestHelper helper) {
        var husk = helper.spawnWithNoFreeWill(EntityTypes.HUSK, 10.5f, 1.0f, 4.5f);
        rideWith(helper, "TestRideHit", (bot, horse) -> {
            // Armed and badly hurt: on foot this is a fight-back, and at 5 health a flight.
            if (!InventoryOps.has(bot.player, Items.IRON_SWORD, 1)) bot.player.getInventory().setItem(5, new ItemStack(Items.IRON_SWORD));
            bot.player.setHealth(5f);
            bot.player.setLastHurtByMob(husk);
        }, (bot, events) -> {
            helper.assertTrue(!events.contains("flee") && !events.contains("fled") && !events.contains("fought") && !events.contains("fight"),
                    "a reflex took over: " + events);
            husk.discard();
        });
    }

    @GameTest(maxTicks = 2400, padding = 24)
    public void ridesMinecartOnPoweredRails(GameTestHelper helper) {
        ground(helper, 23, 5);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 0, 23, 5);
        helper.setBlock(1, 1, 2, Blocks.STONE);
        helper.setBlock(16, 1, 2, Blocks.STONE);
        for (int x = 2; x <= 15; x++) {
            boolean powered = x <= 9;
            if (powered) {
                helper.setBlock(x, 0, 2, Blocks.REDSTONE_BLOCK);
                helper.setBlock(x, 1, 2, Blocks.POWERED_RAIL.defaultBlockState()
                        .setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST).setValue(PoweredRailBlock.POWERED, true));
            } else {
                helper.setBlock(x, 1, 2, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
            }
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestRideCart", 2.5, 0.5);
        Bot bot = s.bot();
        AiPlayer p = bot.player;
        p.getInventory().setItem(0, new ItemStack(Items.MINECART));
        double startX = p.getX();
        run(bot, new RideMinecartRoutine());
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(!bot.executor.busy(), "still busy: " + bot.executor.currentDescription() + " / " + events);
            helper.assertTrue(events.contains("finished: rode a minecart"), "not finished: " + events);
            helper.assertTrue(p.getX() - startX >= 8, "the bot moved only " + (p.getX() - startX));
            helper.assertTrue(!p.isPassenger(), "still riding");
            var around = new AABB(p.position(), p.position()).inflate(12);
            String state = " carts " + helper.getLevel().getEntitiesOfClass(AbstractMinecart.class, around)
                    + " items " + helper.getLevel().getEntitiesOfClass(ItemEntity.class, around) + " bot " + p.position();
            helper.assertTrue(InventoryOps.has(p, Items.MINECART, 1), "no minecart in the inventory: " + events + state);
            GameTestKit.remove(s);
            release.run();
        });
    }
}
