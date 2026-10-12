package com.aiciv.gametest;

import com.aiciv.actions.Approach;
import com.aiciv.actions.MineAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.nav.NavBudget;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.MenuOps;
import com.aiciv.world.PlacedBlocks;
import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

/** The shared world toolkit (Task 1): placed-block memory, block and entity use, menus, finding and approaching. */
public final class ToolkitGameTests {
    @GameTest(maxTicks = 40)
    public void kitSpawnsAndRemovesABot(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestKitSpawn", 3.5, 3.5);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(s.bot().context.attachments() == s.bot().attachments, "the live context hands out the bot's attachments");
            s.bot().context.event("a test event");
            helper.assertTrue(GameTestKit.events(s.bot()).contains("a test event"), "event() did not reach the log: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
            helper.assertTrue(s.manager().bot("TestKitSpawn").isEmpty(), "the bot is still online");
            helper.succeed();
        });
    }

    /** A bare AI player for toolkit calls that need no action loop; removed after {@code maxTicks - 5} at the latest. */
    private static AiPlayer player(GameTestHelper helper, String name, Vec3 at, int maxTicks) {
        MinecraftServer server = helper.getLevel().getServer();
        AiPlayer bot = AiPlayer.join(server, name, () -> {});
        bot.moveTo(helper.getLevel(), helper.absoluteVec(at), -90.0F);
        helper.runAfterDelay(maxTicks - 5, () -> leave(bot));
        return bot;
    }

    private static void leave(AiPlayer bot) {
        MinecraftServer server = bot.level().getServer();
        if (server.getPlayerList().getPlayer(bot.getUUID()) == bot) server.getPlayerList().remove(bot);
    }

    @GameTest(maxTicks = 60)
    public void placementIsRecordedAndForgotten(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        AiPlayer bot = player(helper, "TestKitPlace", new Vec3(1.5, 1, 3.5), 60);
        bot.getInventory().setItem(0, new ItemStack(Items.DIRT, 4));
        helper.runAfterDelay(3, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos target = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockPlacer.PlaceResult placed = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
            helper.assertTrue(placed == BlockPlacer.PlaceResult.PLACED, "place gave " + placed);
            PlacedBlocks blocks = PlacedBlocks.of(level.getServer());
            var at = blocks.at(level, target);
            helper.assertTrue(at.isPresent() && at.get().placer().equals("TestKitPlace") && at.get().block().equals("dirt"),
                    "placement not recorded: " + at);
            level.destroyBlock(target, false);
            helper.assertTrue(blocks.at(level, target).isEmpty(), "a broken block is still remembered as placed");
            leave(bot);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void doorRecordsBothHalves(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        AiPlayer bot = player(helper, "TestKitDoor", new Vec3(1.5, 1, 3.5), 60);
        bot.getInventory().setItem(0, new ItemStack(Items.OAK_DOOR));
        helper.runAfterDelay(3, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos lower = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockPlacer.PlaceResult placed = BlockPlacer.place(bot, lower, s -> s.is(Items.OAK_DOOR));
            helper.assertTrue(placed == BlockPlacer.PlaceResult.PLACED, "place gave " + placed);
            helper.assertBlockPresent(Blocks.OAK_DOOR, new BlockPos(3, 2, 3));
            PlacedBlocks blocks = PlacedBlocks.of(level.getServer());
            helper.assertTrue(blocks.isPlaced(level, lower), "the lower half is not recorded");
            helper.assertTrue(blocks.isPlaced(level, lower.above()), "the upper half is not recorded");
            leave(bot);
            helper.succeed();
        });
    }

    /** A 2-high dirt wall across the box at x = 4 on a bedrock floor (so nothing can be dug under it). */
    private static void dirtWall(GameTestHelper helper, boolean recorded) {
        GameTestKit.floor(helper, 8, Blocks.BEDROCK);
        ServerLevel level = helper.getLevel();
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(4, y, z, Blocks.DIRT);
                if (recorded) PlacedBlocks.of(level.getServer()).record(level, helper.absolutePos(new BlockPos(4, y, z)), "Steve");
            }
        }
    }

    private static int wallLeft(GameTestHelper helper) {
        int n = 0;
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) if (helper.getBlockState(new BlockPos(4, y, z)).is(Blocks.DIRT)) n++;
        return n;
    }

    private static LevelNavigator walkTo(GameTestHelper helper, AiPlayer bot, BlockPos goalCell) {
        NavBudget budget = new NavBudget(NavBudget.DEFAULT_NANOS, System::nanoTime);
        LevelNavigator nav = new LevelNavigator(bot, budget, new BlockBreaker(bot));
        BlockPos goal = helper.absolutePos(goalCell);
        nav.goTo(new NearGoal(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.5));
        helper.onEachTick(() -> {
            budget.tick();
            nav.tick();
        });
        return nav;
    }

    @GameTest(maxTicks = 400)
    public void navigationNeverDigsPlacedBlocks(GameTestHelper helper) {
        dirtWall(helper, true);
        AiPlayer bot = player(helper, "TestKitWall", new Vec3(1.5, 1, 3.5), 400);
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_SHOVEL));
        LevelNavigator nav = walkTo(helper, bot, new BlockPos(6, 1, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(wallLeft(helper) == 16, "the bot dug into a wall someone placed");
            helper.assertTrue(nav.status().state() == NavStatus.State.FAILED, "navigator is " + nav.status().state());
            leave(bot);
        });
    }

    @GameTest(maxTicks = 400)
    public void navigationDigsANaturalWall(GameTestHelper helper) {
        dirtWall(helper, false);
        AiPlayer bot = player(helper, "TestKitDig", new Vec3(1.5, 1, 3.5), 400);
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_SHOVEL));
        LevelNavigator nav = walkTo(helper, bot, new BlockPos(6, 1, 3));
        helper.succeedWhen(() -> {
            helper.assertTrue(nav.status().state() == NavStatus.State.ARRIVED, "navigator is " + nav.status());
            helper.assertTrue(wallLeft(helper) < 16, "the bot got through without digging?");
            leave(bot);
        });
    }

    @GameTest(maxTicks = 600)
    public void mineSkipsPlacedBlocks(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
        helper.setBlock(6, 1, 3, Blocks.STONE);
        ServerLevel level = helper.getLevel();
        PlacedBlocks.of(level.getServer()).record(level, helper.absolutePos(new BlockPos(3, 1, 3)), "Steve");
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestKitMine", 1.5, 3.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        s.bot().executor.replace(List.of(new MineAction("stone", 1)), s.bot().context);
        helper.runAfterDelay(595, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: mine "), "mine did not finish: " + events);
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 1, 3));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(6, 1, 3));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 60)
    public void blockUseRefusesThroughWall(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 3; y++) helper.setBlock(2, y, z, Blocks.GLASS);
        BlockPos leverCell = new BlockPos(3, 1, 3);
        helper.setBlock(leverCell, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        AiPlayer bot = player(helper, "TestKitLever", new Vec3(1.5, 1, 3.5), 60);
        helper.runAfterDelay(3, () -> {
            BlockPos lever = helper.absolutePos(leverCell);
            BlockUse.Result blocked = BlockUse.click(bot, lever);
            helper.assertTrue(blocked == BlockUse.Result.NOT_VISIBLE, "through glass gave " + blocked);
            helper.assertTrue(!helper.getBlockState(leverCell).getValue(LeverBlock.POWERED), "the lever was flipped through the glass");
            for (int z = 0; z < 8; z++) for (int y = 1; y <= 3; y++) helper.setBlock(2, y, z, Blocks.AIR);
            BlockUse.Result used = BlockUse.click(bot, lever);
            helper.assertTrue(used == BlockUse.Result.USED, "in the open gave " + used);
            helper.assertTrue(helper.getBlockState(leverCell).getValue(LeverBlock.POWERED), "the lever is not powered");
            leave(bot);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void placerRefusesThroughWall(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) helper.setBlock(2, y, z, Blocks.STONE);
        helper.setBlock(5, 2, 3, Blocks.STONE); // the target's only solid neighbour, beyond the wall
        AiPlayer bot = player(helper, "TestKitPlaceWall", new Vec3(1.5, 1, 3.5), 60);
        bot.getInventory().setItem(0, new ItemStack(Items.DIRT, 4));
        helper.runAfterDelay(3, () -> {
            BlockPos target = helper.absolutePos(new BlockPos(4, 2, 3));
            BlockPlacer.PlaceResult blocked = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
            helper.assertTrue(blocked == BlockPlacer.PlaceResult.NOT_VISIBLE, "through the wall gave " + blocked);
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(4, 2, 3));
            for (int z = 0; z < 8; z++) for (int y = 1; y <= 2; y++) helper.setBlock(2, y, z, Blocks.AIR);
            BlockPlacer.PlaceResult placed = BlockPlacer.place(bot, target, s -> s.is(Items.DIRT));
            helper.assertTrue(placed == BlockPlacer.PlaceResult.PLACED, "in the open gave " + placed);
            leave(bot);
            helper.succeed();
        });
    }

    private static int chestCount(GameTestHelper helper, BlockPos cell, Item item) {
        ChestBlockEntity chest = helper.getBlockEntity(cell, ChestBlockEntity.class);
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) n += chest.getItem(i).getCount();
        return n;
    }

    private static int containerSlotHolding(ChestMenu menu, Item item) {
        for (Slot slot : MenuOps.containerSlots(menu)) if (slot.getItem().is(item)) return slot.index;
        return -1;
    }

    @GameTest(maxTicks = 60)
    public void menuOpsMovesPartialStacks(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos chestCell = new BlockPos(3, 1, 3);
        helper.setBlock(chestCell, Blocks.CHEST);
        AiPlayer bot = player(helper, "TestKitMenu", new Vec3(1.5, 1, 3.5), 60);
        bot.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        helper.runAfterDelay(3, () -> {
            ChestMenu menu = MenuOps.open(bot, helper.absolutePos(chestCell), ChestMenu.class);
            helper.assertTrue(menu != null, "the chest did not open");
            int in = MenuOps.moveIn(bot, menu, s -> s.is(Items.COBBLESTONE), 10);
            helper.assertTrue(in == 10, "moveIn moved " + in);
            helper.assertTrue(chestCount(helper, chestCell, Items.COBBLESTONE) == 10, "chest has " + chestCount(helper, chestCell, Items.COBBLESTONE));
            helper.assertTrue(InventoryOps.count(bot, s -> s.is(Items.COBBLESTONE)) == 54, "bot has " + InventoryOps.count(bot, s -> s.is(Items.COBBLESTONE)));
            int out = MenuOps.moveOut(bot, menu, containerSlotHolding(menu, Items.COBBLESTONE), 4);
            helper.assertTrue(out == 4, "moveOut moved " + out);
            helper.assertTrue(chestCount(helper, chestCell, Items.COBBLESTONE) == 6, "chest has " + chestCount(helper, chestCell, Items.COBBLESTONE));
            helper.assertTrue(InventoryOps.count(bot, s -> s.is(Items.COBBLESTONE)) == 58, "bot has " + InventoryOps.count(bot, s -> s.is(Items.COBBLESTONE)));
            helper.assertTrue(menu.getCarried().isEmpty(), "something is still carried");
            MenuOps.close(bot);
            leave(bot);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void carriedItemIsNeverLost(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos chestCell = new BlockPos(3, 1, 3);
        helper.setBlock(chestCell, Blocks.CHEST);
        helper.getBlockEntity(chestCell, ChestBlockEntity.class).setItem(0, new ItemStack(Items.STONE, 20));
        AiPlayer bot = player(helper, "TestKitFull", new Vec3(1.5, 1, 3.5), 60);
        for (int i = 0; i < 36; i++) bot.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        helper.runAfterDelay(3, () -> {
            ChestMenu menu = MenuOps.open(bot, helper.absolutePos(chestCell), ChestMenu.class);
            helper.assertTrue(menu != null, "the chest did not open");
            int partial = MenuOps.moveOut(bot, menu, containerSlotHolding(menu, Items.STONE), 10);
            int whole = MenuOps.moveOut(bot, menu, containerSlotHolding(menu, Items.STONE), 64);
            helper.assertTrue(partial == 0 && whole == 0, "moved " + partial + " and " + whole + " into a full inventory");
            helper.assertTrue(menu.getCarried().isEmpty(), "something is still carried");
            helper.assertTrue(chestCount(helper, chestCell, Items.STONE) == 20, "chest has " + chestCount(helper, chestCell, Items.STONE));
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(4)).size();
            helper.assertTrue(dropped == 0, dropped + " item entities were dropped");
            MenuOps.close(bot);
            leave(bot);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void closeReturnsCarriedStack(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos chestCell = new BlockPos(3, 1, 3);
        helper.setBlock(chestCell, Blocks.CHEST);
        AiPlayer bot = player(helper, "TestKitClose", new Vec3(1.5, 1, 3.5), 60);
        bot.getInventory().setItem(5, new ItemStack(Items.COBBLESTONE, 64));
        helper.runAfterDelay(3, () -> {
            ChestMenu menu = MenuOps.open(bot, helper.absolutePos(chestCell), ChestMenu.class);
            helper.assertTrue(menu != null, "the chest did not open");
            OptionalInt slot = MenuOps.inventorySlot(menu, bot, 5);
            helper.assertTrue(slot.isPresent(), "no menu slot for inventory index 5");
            menu.clicked(slot.getAsInt(), 0, ContainerInput.PICKUP, bot);
            helper.assertTrue(menu.getCarried().getCount() == 64, "nothing was picked up");
            MenuOps.close(bot);
            helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "the chest menu is still open");
            helper.assertTrue(menu.getCarried().isEmpty() && bot.inventoryMenu.getCarried().isEmpty(), "a stack is still carried");
            int held = InventoryOps.count(bot, s -> s.is(Items.COBBLESTONE));
            helper.assertTrue(held == 64, "the bot holds " + held + " cobblestone");
            Container chest = helper.getBlockEntity(chestCell, ChestBlockEntity.class);
            helper.assertTrue(chest.isEmpty(), "the stack went into the chest");
            leave(bot);
            helper.succeed();
        });
    }

    /** The chest at (5,1,4) is walled in stone; its east face looks into a closed one-block pocket nobody can see into. */
    private static BlockPos sealedChest(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos chest = new BlockPos(5, 1, 4);
        helper.setBlock(chest, Blocks.CHEST);
        for (BlockPos p : List.of(new BlockPos(4, 1, 4), new BlockPos(5, 1, 3), new BlockPos(5, 1, 5), new BlockPos(5, 2, 4),
                new BlockPos(7, 1, 4), new BlockPos(6, 1, 3), new BlockPos(6, 1, 5), new BlockPos(6, 2, 4))) {
            helper.setBlock(p, Blocks.STONE);
        }
        return chest;
    }

    private static void approach(GameTestHelper helper, String name, BlockPos chestCell, int maxTicks,
                                 java.util.function.Consumer<Approach.Status> check) {
        GameTestKit.Spawned s = GameTestKit.spawn(helper, name, 1.5, 4.5);
        Approach approach = Approach.block(helper.absolutePos(chestCell), 4.0);
        Approach.Status[] last = {null};
        helper.onEachTick(() -> {
            if (last[0] == null || last[0].state() == Approach.State.RUNNING) last[0] = approach.tick(s.bot().context);
        });
        helper.runAfterDelay(maxTicks - 5, () -> GameTestKit.remove(s));
        helper.succeedWhen(() -> {
            helper.assertTrue(last[0] != null && last[0].state() != Approach.State.RUNNING, "still approaching");
            check.accept(last[0]);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void approachFailsWhenTargetHidden(GameTestHelper helper) {
        BlockPos chest = sealedChest(helper);
        approach(helper, "TestKitHidden", chest, 300, status -> {
            helper.assertTrue(status.state() == Approach.State.FAILED, "approach gave " + status);
            helper.assertTrue(status.reason().contains("can't see"), "reason: " + status.reason());
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 4));
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(5, 2, 4));
        });
    }

    @GameTest(maxTicks = 300)
    public void approachArrivesAtAVisibleChest(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos chest = new BlockPos(6, 1, 4);
        helper.setBlock(chest, Blocks.CHEST);
        approach(helper, "TestKitSeen", chest, 300, status ->
                helper.assertTrue(status.state() == Approach.State.ARRIVED, "approach gave " + status));
    }
}
