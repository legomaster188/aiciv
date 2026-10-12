package com.aiciv.gametest;

import com.aiciv.farm.BreedAction;
import com.aiciv.farm.FishAction;
import com.aiciv.farm.HarvestAction;
import com.aiciv.farm.PlantAction;
import com.aiciv.farm.TillAction;
import com.aiciv.modules.ToolModules;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Farming, breeding and fishing (Task 6). Bot names start with TestFarm. */
public final class FarmGameTests {
    private static final BlockState MOIST_FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);

    @GameTest(maxTicks = 20)
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("farm")), "the farm module is not registered");
        helper.succeed();
    }

    private static int count(GameTestHelper helper, GameTestKit.Spawned s, net.minecraft.world.item.Item item) {
        return InventoryOps.count(s.bot().player, st -> st.is(item));
    }

    @GameTest(maxTicks = 600)
    public void tillsNearWater(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(4, 0, 4, Blocks.WATER);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmTill", 1.5, 1.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WOODEN_HOE));
        s.bot().executor.replace(List.of(new TillAction(3)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: tilled 3"), "till did not finish: " + events);
            int farmland = 0;
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) {
                    if (helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.FARMLAND)) {
                        farmland++;
                        helper.assertTrue(Math.abs(x - 4) <= 4 && Math.abs(z - 4) <= 4, "farmland at " + x + "," + z + " is far from the water");
                    }
                }
            }
            helper.assertTrue(farmland == 3, "expected 3 farmland blocks, found " + farmland);
            helper.assertTrue(s.bot().player.getInventory().getItem(0).getDamageValue() >= 1, "the hoe took no damage");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void tillNeedsAHoe(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmNoHoe", 3.5, 3.5);
        s.bot().executor.replace(List.of(new TillAction(2)), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("needs a hoe"), "no failure yet: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void plantsSeeds(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        for (int x = 2; x <= 4; x++) helper.setBlock(x, 0, 2, MOIST_FARMLAND);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmPlant", 3.5, 5.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
        s.bot().executor.replace(List.of(new PlantAction("wheat_seeds", 3)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: planted 3 wheat_seeds"), "plant did not finish: " + events);
            for (int x = 2; x <= 4; x++) {
                BlockState above = helper.getBlockState(new BlockPos(x, 1, 2));
                helper.assertTrue(above.is(Blocks.WHEAT) && above.getValue(CropBlock.AGE) == 0, "no young wheat at " + x + ": " + above);
            }
            helper.assertTrue(count(helper, s, Items.WHEAT_SEEDS) == 0, "seeds left over");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 1200)
    public void harvestReplants(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(x, 0, 2, MOIST_FARMLAND);
            helper.setBlock(x, 1, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmHarvest", 3.5, 5.5);
        s.bot().executor.replace(List.of(new HarvestAction(3)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: harvested 3 wheat (replanted 3)"), "harvest did not finish: " + events);
            helper.assertTrue(count(helper, s, Items.WHEAT) >= 3, "fewer than 3 wheat held");
            for (int x = 2; x <= 4; x++) {
                BlockState above = helper.getBlockState(new BlockPos(x, 1, 2));
                helper.assertTrue(above.is(Blocks.WHEAT) && above.getValue(CropBlock.AGE) == 0, "not replanted at " + x + ": " + above);
            }
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void harvestFailsWithNothingRipe(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(2, 0, 2, MOIST_FARMLAND);
        helper.setBlock(2, 1, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmUnripe", 3.5, 5.5);
        s.bot().executor.replace(List.of(new HarvestAction(3)), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("no ripe crops in sight"), "no failure yet: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void breedsCows(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Cow a = helper.spawnWithNoFreeWill(EntityTypes.COW, 2, 1, 2);
        Cow b = helper.spawnWithNoFreeWill(EntityTypes.COW, 5, 1, 2);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmBreed", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        s.bot().executor.replace(List.of(new BreedAction("cow")), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: bred two cows"), "breed did not finish: " + GameTestKit.events(s.bot()));
            helper.assertTrue(a.isInLove() && b.isInLove(), "the cows are not both in love");
            helper.assertTrue(count(helper, s, Items.WHEAT) == 0, "wheat left over");
            a.discard();
            b.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void breedFailsWithWrongFood(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Pig a = helper.spawnWithNoFreeWill(EntityTypes.PIG, 2, 1, 2);
        Pig b = helper.spawnWithNoFreeWill(EntityTypes.PIG, 5, 1, 2);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmWrong", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 2));
        s.bot().executor.replace(List.of(new BreedAction("pig")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed: breed pig") && events.contains("carrot"), "no helpful failure yet: " + events);
            helper.assertTrue(!((Animal) a).isInLove() && !((Animal) b).isInLove(), "a pig fell in love on the wrong food");
            a.discard();
            b.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 2400, padding = 24)
    public void fishesFromPool(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 16);
        GameTestKit.floor(helper, 16, Blocks.STONE);
        // A 5x5x2 pool sunk into the floor: stone underneath, water in the floor layer and the one below.
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                helper.setBlock(x, -2, z, Blocks.STONE);
                helper.setBlock(x, -1, z, Blocks.STONE);
            }
        }
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                helper.setBlock(x, -1, z, Blocks.WATER);
                helper.setBlock(x, 0, z, Blocks.WATER);
            }
        }
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmFish", 4.5, 8.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.FISHING_ROD));
        s.bot().executor.replace(List.of(new FishAction(1)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: caught 1"), "no catch yet: " + events);
            helper.assertTrue(events.contains("caught "), "no caught event: " + events);
            helper.assertTrue(s.bot().player.fishing == null, "the line is still out");
            int items = 0;
            for (ItemStack st : s.bot().player.getInventory().getNonEquipmentItems()) {
                if (!st.isEmpty() && !st.is(Items.FISHING_ROD)) items += st.getCount();
            }
            helper.assertTrue(items >= 1, "no new item in the inventory");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 60)
    public void cropsSectionDescribesWhatTheBotSees(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(2, 0, 2, MOIST_FARMLAND);
        helper.setBlock(2, 1, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        helper.setBlock(3, 0, 2, MOIST_FARMLAND);
        helper.setBlock(5, 0, 2, Blocks.WATER);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmSense", 3.5, 5.5);
        helper.runAfterDelay(5, () -> {
            var sections = new com.aiciv.farm.FarmModule().sections(s.bot().player, s.bot().attachments);
            helper.assertTrue(sections.size() == 1, "expected one section: " + sections);
            var crops = sections.get(0);
            helper.assertTrue(crops.items().stream().anyMatch(i -> i.startsWith("wheat x1 ripe")), "no ripe wheat line: " + crops.items());
            helper.assertTrue(crops.items().contains("free farmland x1"), "no free farmland line: " + crops.items());
            helper.assertTrue(crops.tags().containsAll(List.of("crop_ripe", "farmland_free", "water_near")), "tags: " + crops.tags());
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void fishNeedsARodAndWater(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestFarmDry", 3.5, 3.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.FISHING_ROD));
        s.bot().executor.replace(List.of(new FishAction(1)), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("no open water within 8"), "no failure yet: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
        });
    }
}
