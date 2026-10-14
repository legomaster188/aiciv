package com.aiciv.gametest;

import com.aiciv.animals.DyeSheepAction;
import com.aiciv.animals.LeashAction;
import com.aiciv.animals.MilkAction;
import com.aiciv.animals.SaddleAction;
import com.aiciv.animals.ShearAction;
import com.aiciv.animals.TameAction;
import com.aiciv.animals.TieLeadAction;
import com.aiciv.animals.UnleashAction;
import com.aiciv.modules.ToolModules;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Task 8: taming and pets. Bot names start with TestPet. */
public final class AnimalGameTests {
    private static int count(GameTestKit.Spawned s, Item item) {
        return InventoryOps.count(s.bot().player, st -> st.is(item));
    }

    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("animals")), "animals module missing");
        helper.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void tamesWolfWithBones(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Wolf wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, 3, 1, 3);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetWolf", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.BONE, 16));
        s.bot().executor.replace(List.of(new TameAction("wolf")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: tamed the wolf"), "taming did not finish: " + events);
            helper.assertTrue(wolf.isTame() && wolf.isOwnedBy(s.bot().player), "the wolf is not tame and owned by the bot");
            helper.assertTrue(count(s, Items.BONE) < 16, "no bone was used");
            wolf.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200)
    public void tameFailsWithWrongFood(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Wolf wolf = helper.spawnWithNoFreeWill(EntityTypes.WOLF, 3, 1, 3);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetWrong", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 5));
        s.bot().executor.replace(List.of(new TameAction("wolf")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed:") && events.contains("tamed with bone"), "no helpful failure yet: " + events);
            helper.assertTrue(count(s, Items.WHEAT) == 5, "wheat was used");
            helper.assertTrue(!wolf.isTame(), "the wolf got tame");
            wolf.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 2400)
    public void tamesHorseByRiding(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Horse horse = helper.spawn(EntityTypes.HORSE, 3, 1, 3);
        horse.setTemper(horse.getMaxTemper() - 1);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetHorse", 3.5, 6.5);
        s.bot().executor.replace(List.of(new TameAction("horse")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: tamed the horse"), "taming did not finish: " + events);
            helper.assertTrue(horse.isTamed(), "the horse is not tame");
            helper.assertTrue(s.bot().player.getVehicle() == null, "the bot is still riding");
            horse.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void saddlesTamedHorse(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Horse horse = helper.spawnWithNoFreeWill(EntityTypes.HORSE, 3, 1, 3);
        horse.setTamed(true);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetSaddle", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.SADDLE));
        s.bot().executor.replace(List.of(new SaddleAction("horse")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: saddled the horse"), "saddling did not finish: " + events);
            helper.assertTrue(horse.isSaddled(), "the horse is not saddled");
            helper.assertTrue(count(s, Items.SADDLE) == 0, "the saddle was not consumed");
            horse.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void saddleNeedsTamingFirst(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Horse horse = helper.spawnWithNoFreeWill(EntityTypes.HORSE, 3, 1, 3);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetUntame", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.SADDLE));
        s.bot().executor.replace(List.of(new SaddleAction("horse")), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("tame the horse first"), "no tame-first failure: " + GameTestKit.events(s.bot()));
            helper.assertTrue(count(s, Items.SADDLE) == 1, "the saddle was used");
            horse.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 900)
    public void leashesAndTiesCow(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        helper.setBlock(6, 1, 2, Blocks.OAK_FENCE);
        Cow cow = helper.spawnWithNoFreeWill(EntityTypes.COW, 3, 1, 2);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetLead", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.LEAD));
        s.bot().executor.replace(List.of(new LeashAction("cow"), new TieLeadAction(null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("tied 1 animal to the fence"), "tying did not finish: " + events);
            helper.assertTrue(cow.getLeashHolder() instanceof LeashFenceKnotEntity, "the cow is not tied to a fence: " + cow.getLeashHolder());
            cow.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 900)
    public void unleashesCowTiedToFence(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        BlockPos fence = new BlockPos(6, 1, 2);
        helper.setBlock(fence, Blocks.OAK_FENCE);
        Cow cow = helper.spawnWithNoFreeWill(EntityTypes.COW, 4, 1, 2);
        cow.setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(helper.getLevel(), helper.absolutePos(fence)), true);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetFree", 3.5, 6.5);
        s.bot().executor.replace(List.of(new UnleashAction("cow")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: took the lead off the cow"), "unleashing did not finish: " + events);
            helper.assertTrue(!cow.isLeashed(), "the cow is still leashed");
            helper.assertTrue(count(s, Items.LEAD) == 1, "the lead was not picked up, bot holds " + count(s, Items.LEAD));
            cow.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void milksCow(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Cow cow = helper.spawnWithNoFreeWill(EntityTypes.COW, 3, 1, 3);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetMilk", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.BUCKET));
        s.bot().executor.replace(List.of(new MilkAction(null)), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: milked the cow"), "milking did not finish: " + GameTestKit.events(s.bot()));
            helper.assertTrue(count(s, Items.MILK_BUCKET) == 1 && count(s, Items.BUCKET) == 0, "no milk bucket");
            cow.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void shearsSheep(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Sheep sheep = helper.spawnWithNoFreeWill(EntityTypes.SHEEP, 3, 1, 3);
        sheep.setColor(DyeColor.WHITE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetShear", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.SHEARS));
        s.bot().executor.replace(List.of(new ShearAction(null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: sheared the sheep (got ") && events.contains("white_wool"), "shearing did not finish: " + events);
            helper.assertTrue(sheep.isSheared(), "the sheep is not sheared");
            helper.assertTrue(count(s, Items.WOOL.white()) >= 1, "no wool was picked up");
            sheep.discard();
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void dyesSheepRed(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.DIRT);
        Sheep sheep = helper.spawnWithNoFreeWill(EntityTypes.SHEEP, 3, 1, 3);
        sheep.setColor(DyeColor.WHITE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestPetDye", 3.5, 6.5);
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.DYE.pick(DyeColor.RED)));
        s.bot().executor.replace(List.of(new DyeSheepAction("red")), s.bot().context);
        helper.succeedWhen(() -> {
            helper.assertTrue(GameTestKit.events(s.bot()).contains("finished: dyed the sheep red"), "dyeing did not finish: " + GameTestKit.events(s.bot()));
            helper.assertTrue(sheep.getColor() == DyeColor.RED, "the sheep is " + sheep.getColor());
            sheep.discard();
            GameTestKit.remove(s);
        });
    }
}
