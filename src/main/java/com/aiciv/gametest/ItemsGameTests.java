package com.aiciv.gametest;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionFactory;
import com.aiciv.bot.AiPlayer;
import com.aiciv.items.CarvePumpkinAction;
import com.aiciv.items.DrinkAction;
import com.aiciv.items.EmptyBucketAction;
import com.aiciv.items.FillBucketAction;
import com.aiciv.items.IgniteAction;
import com.aiciv.items.LinkCompassAction;
import com.aiciv.items.ThrowAction;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.ToolModules;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonParser;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

/** Task 7: buckets, bone meal, fire, potions, throwables, maps and compasses. Bot names start with TestItem. */
public final class ItemsGameTests {
    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("items")), "items module missing");
        helper.succeed();
    }

    private static void give(GameTestKit.Spawned s, ItemStack... stacks) {
        for (int i = 0; i < stacks.length; i++) s.bot().player.getInventory().setItem(i, stacks[i]);
    }

    private static Action call(String tool, String json) {
        ActionFactory.Result r = ActionFactory.create(ToolCall.of(tool, JsonParser.parseString(json).getAsJsonObject()));
        return ((ActionFactory.Result.Ok) r).action();
    }

    @GameTest(maxTicks = 600)
    public void fillsAndEmptiesWaterBucket(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(6, -1, 3, Blocks.STONE);
        helper.setBlock(6, 0, 3, Blocks.WATER);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemBucket", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, new ItemStack(Items.BUCKET));
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 3));
        s.bot().executor.replace(List.of(call("fill_bucket", "{\"fluid\": \"water\"}"), new EmptyBucketAction(cell.getX(), cell.getY(), cell.getZ())), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("filled a water_bucket"), "not filled: " + events);
            helper.assertTrue(events.contains("poured water at"), "not poured: " + events);
            helper.assertTrue(helper.getLevel().getFluidState(cell).isSourceOfType(Fluids.WATER), "no water source at the cell");
            helper.assertTrue(InventoryOps.has(p, Items.BUCKET, 1) && !InventoryOps.has(p, Items.WATER_BUCKET, 1), "the bucket was not emptied");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void bonemealRipensWheat(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 0, 3, Blocks.FARMLAND);
        BlockPos wheat = new BlockPos(4, 1, 3);
        helper.setBlock(wheat, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemBone", 2.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, new ItemStack(Items.BONE_MEAL, 8));
        s.bot().executor.replace(List.of(call("bone_meal", "{\"count\": 8}")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: used") && events.contains("on wheat (now ripe)"), "not finished: " + events);
            helper.assertTrue(helper.getBlockState(wheat).getValue(CropBlock.AGE) == 7, "the wheat is not ripe");
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.BONE_MEAL)) < 8, "no bone meal was used");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void ignitesCampfireAndNetherrack(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        BlockPos campfire = new BlockPos(4, 1, 2);
        helper.setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        helper.setBlock(4, 1, 5, Blocks.NETHERRACK);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemFire", 2.5, 3.5);
        give(s, new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos c = helper.absolutePos(campfire), n = helper.absolutePos(new BlockPos(4, 1, 5));
        s.bot().executor.replace(List.of(new IgniteAction(c.getX(), c.getY(), c.getZ()), new IgniteAction(n.getX(), n.getY(), n.getZ())), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(helper.getBlockState(campfire).getValue(CampfireBlock.LIT), "the campfire is not lit: " + events);
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 5)).is(Blocks.FIRE), "no fire above the netherrack: " + events);
            helper.assertTrue(events.contains("lit the campfire") && events.contains("set fire on top of"), "unexpected summary: " + events);
            helper.setBlock(4, 2, 5, Blocks.AIR);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 300)
    public void drinksSwiftness(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemDrink", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS), new ItemStack(Items.GLASS_BOTTLE));
        s.bot().executor.replace(List.of(new DrinkAction("swiftness")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: drank swiftness"), "not finished: " + events);
            helper.assertTrue(p.hasEffect(MobEffects.SPEED), "no speed effect");
            helper.assertTrue(InventoryOps.count(p, x -> x.is(Items.POTION)) == 0, "the potion is still held");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 200, padding = 24)
    public void throwsSnowballAtPoint(GameTestHelper helper) {
        GameTestKit.clearBarriers(helper, 16);
        for (int x = 0; x < 16; x++) for (int z = 2; z <= 6; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        Runnable release = GameTestKit.keepEntitiesTicking(helper, 0, 2, 15, 6);
        Vec3 target = helper.absoluteVec(new Vec3(13.5, 1.0, 4.5));
        Snowball[] ball = new Snowball[1];
        AiPlayer[] me = new AiPlayer[1];
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemSnow", 1.5, 4.5, () -> {
            if (ball[0] != null || me[0] == null) return;
            List<Snowball> seen = me[0].level().getEntitiesOfClass(Snowball.class, me[0].getBoundingBox().inflate(3));
            if (!seen.isEmpty()) ball[0] = seen.get(0);
        });
        me[0] = s.bot().player;
        give(s, new ItemStack(Items.SNOWBALL, 4));
        BlockPos at = helper.absolutePos(new BlockPos(13, 0, 4));
        s.bot().executor.replace(List.of(new ThrowAction("snowball", at, null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("threw a snowball at"), "nothing thrown: " + events);
            helper.assertTrue(ball[0] != null && ball[0].isRemoved(), "the snowball has not landed yet");
            double off = ball[0].position().distanceTo(target);
            helper.assertTrue(off <= 2.0, "the snowball landed " + off + " from the point, at " + ball[0].position());
            helper.assertTrue(InventoryOps.count(s.bot().player, x -> x.is(Items.SNOWBALL)) == 3, "a snowball was not used up");
            GameTestKit.remove(s);
            release.run();
        });
    }

    @GameTest(maxTicks = 100)
    public void throwAtUnseenPointFails(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 3; y++) helper.setBlock(4, y, z, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemWall", 2.5, 3.5);
        give(s, new ItemStack(Items.SNOWBALL, 4));
        BlockPos at = helper.absolutePos(new BlockPos(6, 0, 3));
        s.bot().executor.replace(List.of(new ThrowAction("snowball", at, null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("can't see"), "no refusal: " + events);
            helper.assertTrue(InventoryOps.count(s.bot().player, x -> x.is(Items.SNOWBALL)) == 4, "a snowball was thrown");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void makesMap(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemMap", 3.5, 3.5);
        AiPlayer p = s.bot().player;
        give(s, new ItemStack(Items.MAP));
        s.bot().executor.replace(List.of(call("make_map", "{}")), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("made map #"), "no map made: " + events);
            ItemStack map = p.getInventory().getNonEquipmentItems().stream().filter(x -> x.is(Items.FILLED_MAP)).findFirst().orElse(ItemStack.EMPTY);
            helper.assertTrue(!map.isEmpty() && map.get(DataComponents.MAP_ID) != null, "no filled map held");
            MapItemSavedData data = MapItem.getSavedData(map, p.level());
            helper.assertTrue(data != null, "no saved map data");
            helper.assertTrue(Math.abs(data.centerX - p.getX()) <= 64 && Math.abs(data.centerZ - p.getZ()) <= 64,
                    "the map is centred at " + data.centerX + " " + data.centerZ + ", you are at " + p.position());
            helper.assertTrue(!InventoryOps.has(p, Items.MAP, 1), "the empty map is still held");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void compassLineInSnapshot(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemCompass", 3.5, 3.5);
        give(s, new ItemStack(Items.COMPASS));
        helper.runAfterDelay(3, () -> {
            String text = SnapshotFormatter.format(Perceiver.capture(s.bot().player, "none", "nothing yet", x -> x instanceof AiPlayer),
                    SnapshotFormatter.MAX_CHARS);
            GameTestKit.remove(s);
            helper.assertTrue(text.contains("compass points"), "no compass line:\n" + text);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void carvesPumpkin(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.PUMPKIN);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemCarve", 2.5, 3.5);
        give(s, new ItemStack(Items.SHEARS));
        s.bot().executor.replace(List.of(new CarvePumpkinAction(null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 3)).is(Blocks.CARVED_PUMPKIN), "not carved: " + events);
            helper.assertTrue(events.contains("finished: carved the pumpkin"), "not finished: " + events);
            helper.assertTrue(InventoryOps.has(s.bot().player, Items.PUMPKIN_SEEDS, 1), "the seeds were not picked up");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 400)
    public void linksCompassToLodestone(GameTestHelper helper) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        helper.setBlock(4, 1, 3, Blocks.LODESTONE);
        GameTestKit.Spawned s = GameTestKit.spawn(helper, "TestItemLode", 2.5, 3.5);
        give(s, new ItemStack(Items.COMPASS));
        s.bot().executor.replace(List.of(new LinkCompassAction(null)), s.bot().context);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("linked a compass to the lodestone"), "not linked: " + events);
            helper.assertTrue(InventoryOps.count(s.bot().player, x -> x.is(Items.COMPASS) && x.has(DataComponents.LODESTONE_TRACKER)) == 1,
                    "the compass has no lodestone tracker");
            GameTestKit.remove(s);
        });
    }
}
