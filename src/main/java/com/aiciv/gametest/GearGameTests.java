package com.aiciv.gametest;

import com.aiciv.bot.AiPlayer;
import com.aiciv.gear.Armor;
import com.aiciv.gear.GearPlan;
import com.aiciv.gear.GearUpAction;
import com.aiciv.modules.ToolModules;
import com.aiciv.perception.Perceiver;
import com.aiciv.perception.SnapshotFormatter;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Task 9: armor and gear. Bot names start with TestGear. */
public final class GearGameTests {
    private static GameTestKit.Spawned arena(GameTestHelper helper, String name) {
        GameTestKit.floor(helper, 8, Blocks.STONE);
        return GameTestKit.spawn(helper, name, 3.5, 3.5);
    }

    private static void gearUp(GameTestKit.Spawned s, List<String> materials, boolean shield) {
        s.bot().executor.replace(List.of(new GearUpAction(materials, shield)), s.bot().context);
    }

    @GameTest
    public void moduleIsRegistered(GameTestHelper helper) {
        helper.assertTrue(ToolModules.ALL.stream().anyMatch(m -> m.name().equals("gear")), "gear module missing");
        helper.succeed();
    }

    @GameTest(maxTicks = 800)
    public void gearUpCraftsAndWearsFullIronSetAndShield(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearIron");
        // 8 + 7 + 5 + 4 for the set and 1 for the shield; planks for the table and the shield.
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 25));
        s.bot().player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 16));
        gearUp(s, GearPlan.AUTO, true);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: wearing iron_helmet, iron_chestplate, iron_leggings, iron_boots + shield (crafted 5)"),
                    "gear_up did not finish as expected: " + events);
            helper.assertTrue(s.bot().player.getArmorValue() == 15, "armor value " + s.bot().player.getArmorValue());
            helper.assertTrue(s.bot().player.getOffhandItem().is(Items.SHIELD), "no shield in the off hand");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 600)
    public void gearUpMakesWhatLeatherAllowsAndKeepsTheOffhandFreeWithoutShield(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearLeather");
        s.bot().player.getInventory().setItem(0, new ItemStack(Items.LEATHER, 12)); // chestplate 8, then boots 4
        s.bot().player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        gearUp(s, GearPlan.AUTO, false);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: wearing leather_chestplate, leather_boots (crafted 2)"), "unexpected: " + events);
            helper.assertTrue(s.bot().player.getArmorValue() == 4, "armor value " + s.bot().player.getArmorValue());
            helper.assertTrue(s.bot().player.getOffhandItem().isEmpty(), "off hand should stay empty");
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void gearUpWithNothingFailsNamingTheMaterials(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearNone");
        gearUp(s, GearPlan.AUTO, true);
        helper.succeedWhen(() -> {
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("failed: gear up: can't make any armor: need leather, iron_ingot, gold_ingot or diamond"), "unexpected: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 100)
    public void gearUpPutsOnCarriedUpgradeAndKeepsABetterWornPiece(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearKeep");
        AiPlayer p = s.bot().player;
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_HELMET));
        p.getInventory().setItem(1, new ItemStack(Items.IRON_BOOTS));
        gearUp(s, GearPlan.AUTO, false);
        helper.succeedWhen(() -> {
            helper.assertTrue(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "diamond helmet not worn");
            helper.assertTrue(p.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS), "the diamond boots should stay on");
            helper.assertTrue(InventoryOps.has(p, Items.LEATHER_HELMET, 1),
                    "the old helmet should go to the inventory");
            String events = GameTestKit.events(s.bot());
            helper.assertTrue(events.contains("finished: wearing diamond_helmet, diamond_boots"), "unexpected: " + events);
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 40)
    public void armorIsWornByItselfWithinFortyTicks(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearWear");
        s.bot().player.getInventory().setItem(3, new ItemStack(Items.IRON_HELMET));
        s.bot().player.getInventory().setItem(4, new ItemStack(Items.SHIELD));
        helper.succeedWhen(() -> {
            helper.assertTrue(s.bot().player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "helmet not worn yet");
            helper.assertTrue(s.bot().player.getOffhandItem().is(Items.SHIELD), "shield not in the off hand yet");
            helper.assertTrue(GameTestKit.events(s.bot()).contains("put on iron_helmet"), "no event: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
        });
    }

    @GameTest(maxTicks = 80)
    public void autoWearDoesNotSwapForAnEqualOrWorsePiece(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearSame");
        AiPlayer p = s.bot().player;
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        p.getInventory().setItem(3, new ItemStack(Items.GOLDEN_HELMET));
        p.getInventory().setItem(4, new ItemStack(Items.LEATHER_HELMET));
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the iron helmet should stay on");
            helper.assertTrue(!GameTestKit.events(s.bot()).contains("put on"), "unexpected: " + GameTestKit.events(s.bot()));
            GameTestKit.remove(s);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void snapshotShowsArmorDurabilityAndWarnings(GameTestHelper helper) {
        GameTestKit.Spawned s = arena(helper, "TestGearSnap");
        AiPlayer p = s.bot().player;
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamageValue(chest.getMaxDamage() - Math.round(chest.getMaxDamage() * 0.34f));
        p.setItemSlot(EquipmentSlot.CHEST, chest);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - Math.round(pick.getMaxDamage() * 0.09f));
        p.getInventory().setItem(0, pick);
        p.getInventory().setSelectedSlot(0);
        // The armor attribute follows the equipment on the next tick.
        helper.runAfterDelay(3, () -> {
            String text = SnapshotFormatter.format(Perceiver.capture(p, "none", "nothing", x -> x instanceof AiPlayer, s.bot().knownBlocks,
                    s.bot().attachments), SnapshotFormatter.MAX_CHARS);
            GameTestKit.remove(s);
            helper.assertTrue(text.contains("Gear: armor 6"), text);
            helper.assertTrue(text.contains("iron_chestplate 34%"), text);
            helper.assertTrue(text.contains("iron_pickaxe almost broken (9%)"), text);
            helper.assertTrue(text.contains("holding iron_pickaxe 9%"), text);
            helper.succeed();
        });
    }

    @GameTest
    public void armorScoresFromTheGameMatchThePlanningTable(GameTestHelper helper) {
        for (String material : List.of("leather", "chainmail", "golden", "iron", "diamond", "netherite")) {
            for (GearPlan.Slot slot : GearPlan.Slot.values()) {
                String id = GearPlan.pieceId(material, slot);
                helper.assertTrue(Armor.score(id) == GearPlan.tableScore(id), id + ": game " + Armor.score(id) + " table " + GearPlan.tableScore(id));
            }
        }
        helper.assertTrue(Armor.score("diamond_sword") < 0 && Armor.score("stone") < 0, "non-armor should score below zero");
        helper.succeed();
    }
}
