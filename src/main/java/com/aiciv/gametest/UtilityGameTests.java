package com.aiciv.gametest;

import com.aiciv.actions.GoToAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.modules.ToolModules;
import com.aiciv.utility.BrewAction;
import com.aiciv.utility.EnchantAction;
import com.aiciv.utility.SleepAction;
import com.aiciv.utility.UseBlockAction;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.BedBlock;
import java.nio.file.Files;
import com.aiciv.llm.ScriptedBackend;
import com.aiciv.bot.BotManager;
import com.aiciv.bot.Bot;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Sleep, doors, switches, enchanting and brewing (Task 7). Bot names start with TestUtil. */
public final class UtilityGameTests {
    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("utility")), "the utility module is not registered");
        helper.succeed();
    }

    private static void give(GameTestKit.Spawned s, ItemStack... stacks) {
        for (int i = 0; i < stacks.length; i++) s.bot().player.getInventory().setItem(i, stacks[i]);
    }

    private static void wallWithDoor(GameTestHelper helper) {
        for (int z = 0; z < 8; z++) {
            for (int y = 1; y <= 3; y++) helper.setBlock(4, y, z, z == 3 && y <= 2 ? Blocks.AIR : Blocks.OAK_PLANKS);
        }
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(new BlockPos(4, 1, 3), lower);
        helper.setBlock(new BlockPos(4, 2, 3), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    @GameTest(maxTicks = 600)
    public void opensDoorOnTheWay(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        wallWithDoor(helper);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilDoor", 1.5, 3.5);
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 3));
        s.bot().executor.replace(List.of(new GoToAction(goal.getX(), goal.getY(), goal.getZ())), s.bot().context);
        BlockPos door = new BlockPos(4, 1, 3);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: goTo"), "the bot did not arrive: " + events);
            helper.assertTrue(s.bot().player.getX() > helper.absolutePos(new BlockPos(5, 1, 3)).getX(), "the bot is still west of the wall");
            helper.assertBlockPresent(Blocks.OAK_DOOR, door);
            helper.assertTrue(!helper.getBlockState(door).getValue(DoorBlock.OPEN), "the door was left open");
            for (int z = 0; z < 8; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (z == 3 && y <= 2) continue;
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(4, y, z));
                }
            }
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void pressesButtonAndFlipsLever(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(5, 2, 3, Blocks.STONE);
        helper.setBlock(5, 2, 4, Blocks.STONE);
        BlockPos button = new BlockPos(4, 2, 3);
        BlockPos lever = new BlockPos(4, 2, 4);
        helper.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST));
        helper.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.WEST));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilSwitch", 2.5, 3.5);
        BlockPos b = helper.absolutePos(button), l = helper.absolutePos(lever);
        s.bot().executor.replace(List.of(new UseBlockAction(b.getX(), b.getY(), b.getZ()), new UseBlockAction(l.getX(), l.getY(), l.getZ())), s.bot().context);
        boolean[] pressed = new boolean[1];
        helper.succeedWhen(() -> {
            pressed[0] |= helper.getBlockState(button).getValue(BlockStateProperties.POWERED);
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(pressed[0], "the button was never pressed: " + events);
            helper.assertTrue(helper.getBlockState(lever).getValue(LeverBlock.POWERED), "the lever is not on: " + events);
            helper.assertTrue(events.contains("pressed stone_button") && events.contains("flipped lever"), "unexpected summary: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void useBlockRefusesChests(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.CHEST);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilChest", 2.5, 3.5);
        BlockPos chest = helper.absolutePos(new BlockPos(4, 1, 3));
        s.bot().executor.replace(List.of(new UseBlockAction(chest.getX(), chest.getY(), chest.getZ())), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("use look_in_chest"), "no refusal: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600, environment = "aiciv:night")
    public void sleepsInCarriedBed(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilSleep", 3.5, 3.5);
        give(s, new ItemStack(Items.BED.white()));
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(helper.getLevel().isDarkOutside(), "the night environment did not make it dark");
            s.bot().executor.replace(List.of(new SleepAction()), s.bot().context);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(s.bot().player.isSleeping(), "the bot is not asleep: " + GameTestKit.events(s.bot()));
            s.bot().executor.stop(s.bot().context);
            helper.assertTrue(!s.bot().player.isSleeping(), "stop did not wake the bot");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200)
    public void sleepFailsByDay(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        if (helper.getLevel().isDarkOutside() || helper.getLevel().isThundering()) {
            helper.succeed(); // nothing to check when the shared world happens to be at night
            return;
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilDay", 3.5, 3.5);
        give(s, new ItemStack(Items.BED.white()));
        s.bot().executor.replace(List.of(new SleepAction()), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("only sleep at night"), "no refusal: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void enchantsPickaxe(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.ENCHANTING_TABLE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilEnchant", 2.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.LAPIS_LAZULI, 3));
        p.giveExperienceLevels(30);
        s.bot().executor.replace(List.of(new EnchantAction("iron_pickaxe", 1)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: enchanted iron_pickaxe"), "not enchanted: " + events);
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.LAPIS_LAZULI)) == 2, "lapis left: " + InventoryOps.count(p, x -> x.is(Items.LAPIS_LAZULI)));
            helper.assertTrue(p.experienceLevel == 29, "level " + p.experienceLevel);
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.IRON_PICKAXE) && EnchantmentHelper.hasAnyEnchantments(x)) == 1, "the pickaxe has no enchantment");
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the table screen is still open");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1200)
    public void brewsAwkwardPotions(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(3, 0, 5, Blocks.WATER);
        helper.setBlock(4, 1, 3, Blocks.BREWING_STAND);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilBrew", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, new ItemStack(Items.GLASS_BOTTLE, 3), new ItemStack(Items.NETHER_WART), new ItemStack(Items.BLAZE_POWDER));
        s.bot().executor.replace(List.of(new BrewAction("awkward", 3)), s.bot().context);
        helper.succeedWhen(() -> {
            int awkward = InventoryOps.count(p, x -> x.is(Items.POTION) && isAwkward(x));
            helper.assertTrue(awkward == 3, "awkward potions held: " + awkward + "; " + GameTestKit.events(s.bot()));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the stand screen is still open");
            GameTestKit.remove(s);
        });
    }

    private static boolean isAwkward(ItemStack s) {
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        return c != null && c.is(Potions.AWKWARD);
    }

    private static ItemStack water() {
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
    }

    private static int potions(AiPlayer p, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        return InventoryOps.count(p, x -> {
            PotionContents c = x.get(DataComponents.POTION_CONTENTS);
            return x.is(Items.POTION) && c != null && c.is(potion);
        });
    }

    /** Stopping a brew halfway hands the bot back its bottles and ingredient through the stand's menu. */
    @GameTest(maxTicks = 400)
    public void cancelledBrewTakesItsItemsBack(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.BREWING_STAND);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilBrewStop", 2.5, 3.5);
        helper.runAfterDelay(395, () -> GameTestKit.remove(s));
        AiPlayer p = s.bot().player;
        ItemStack bottles = water();
        bottles.setCount(3);
        give(s, bottles, new ItemStack(Items.NETHER_WART), new ItemStack(Items.BLAZE_POWDER));
        s.bot().executor.replace(List.of(new BrewAction("awkward", 3)), s.bot().context);
        BrewingStandBlockEntity be = helper.getBlockEntity(new BlockPos(4, 1, 3), BrewingStandBlockEntity.class);
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            if (!stopped[0] && !be.getItem(3).isEmpty()) { // loaded and brewing
                s.bot().executor.stop(s.bot().context);
                stopped[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stopped[0], "the stand was never loaded: " + GameTestKit.events(s.bot()));
            helper.assertTrue(potions(p, Potions.WATER) == 3, "water bottles back: " + potions(p, Potions.WATER));
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.NETHER_WART)) == 1, "the nether wart was not taken back");
            for (int i = 0; i <= 3; i++) helper.assertTrue(be.getItem(i).isEmpty(), "slot " + i + " still holds " + be.getItem(i));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the stand screen is still open");
            GameTestKit.remove(s);
        });
    }

    /** Finished potions left in a stand (anyone may take them) are collected first, then the brew goes ahead. */
    @GameTest(maxTicks = 1200)
    public void brewCollectsFinishedPotionsFirst(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.BREWING_STAND);
        BrewingStandBlockEntity be = helper.getBlockEntity(new BlockPos(4, 1, 3), BrewingStandBlockEntity.class);
        for (int i = 0; i < 3; i++) be.setItem(i, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestUtilBrewLeft", 2.5, 3.5);
        helper.runAfterDelay(1195, () -> GameTestKit.remove(s));
        AiPlayer p = s.bot().player;
        ItemStack bottles = water();
        bottles.setCount(3);
        give(s, bottles, new ItemStack(Items.NETHER_WART), new ItemStack(Items.BLAZE_POWDER));
        s.bot().executor.replace(List.of(new BrewAction("awkward", 3)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: brewed 3"), "the brew did not finish: " + events);
            helper.assertTrue(potions(p, Potions.AWKWARD) == 6, "awkward potions held: " + potions(p, Potions.AWKWARD));
            helper.assertTrue(p.containerMenu == p.inventoryMenu, "the stand screen is still open");
            GameTestKit.remove(s);
        });
    }

    /** In the nether a bed explodes when used: the bot refuses by the bed rule and never clicks it. */
    @GameTest(maxTicks = 100)
    public void neverSleepsWhereBedsExplode(GameTestHelper helper) throws Exception {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server has no nether");
        BlockPos o = new BlockPos(4000, 100, 4000);
        for (int x = -1; x <= 4; x++) {
            for (int z = -1; z <= 3; z++) {
                nether.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) nether.setBlockAndUpdate(o.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        BlockState foot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        nether.setBlockAndUpdate(o, foot);
        nether.setBlockAndUpdate(o.south(), foot.setValue(BedBlock.PART, BedPart.HEAD));
        BotManager manager = AiCivGameTests.scriptedManager(helper, new ScriptedBackend(List.of()), Files.createTempDirectory("aiciv-gametest"));
        Bot bot = manager.spawn("TestUtilNether", "scripted", "", nether, Vec3.atBottomCenterOf(o.east(2)));
        GameTestKit.Spawned spawned = new GameTestKit.Spawned(manager, bot);
        helper.onEachTick(manager::tickStart);
        helper.runAfterDelay(95, () -> GameTestKit.remove(spawned));
        bot.executor.replace(List.of(new SleepAction()), bot.context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(bot);
            helper.assertTrue(events.contains("beds explode here"), "no refusal by the bed rule: " + events);
            helper.assertTrue(nether.getBlockState(o).getBlock() instanceof BedBlock && !bot.player.isSleeping(), "the bed was used");
            GameTestKit.remove(spawned);
        });
    }
}
