package com.aiciv.gear;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.gear.GearPlan.Craft;
import com.aiciv.gear.GearPlan.Equip;
import com.aiciv.gear.GearPlan.Slot;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GearPlanTest {
    private static final Map<Slot, String> NONE = new EnumMap<>(Slot.class);

    private static GearPlan.Job next(Map<Slot, String> worn, Map<String, Integer> inv, List<String> craftable) {
        return GearPlan.next(worn, inv, GearPlan.AUTO, craftable::contains, GearPlan::tableScore, Set.of());
    }

    @Test
    void tableScoresArePointsPlusToughness() {
        assertEquals(1, GearPlan.tableScore("leather_helmet"));
        assertEquals(6, GearPlan.tableScore("iron_chestplate"));
        assertEquals(5, GearPlan.tableScore("diamond_helmet")); // 3 points, 2 toughness
        assertEquals(11, GearPlan.tableScore("netherite_chestplate")); // 8 + 3
        assertEquals(-1, GearPlan.tableScore("diamond_sword"));
        assertEquals(-1, GearPlan.tableScore("turtle_helmet"));
    }

    @Test
    void betterCarriedPieceBeatsCraftingAndStrictlyBetterOnly() {
        GearPlan.Job job = next(Map.of(Slot.HEAD, "leather_helmet"), Map.of("iron_helmet", 1), List.of("diamond_helmet"));
        assertEquals(new Equip("iron_helmet"), job);
        // equal score is not an upgrade, and neither is a worse piece
        assertNull(next(Map.of(Slot.HEAD, "iron_helmet"), Map.of("golden_helmet", 1), List.of()));
        assertNull(next(Map.of(Slot.HEAD, "iron_helmet"), Map.of("leather_helmet", 1), List.of()));
    }

    @Test
    void emptySlotTakesAnyCarriedPieceBestFirst() {
        GearPlan.Job job = next(NONE, Map.of("leather_boots", 1, "iron_boots", 1), List.of());
        assertEquals(new Equip("iron_boots"), job);
    }

    @Test
    void craftsTheBestAffordableMaterialInFillOrder() {
        assertEquals(new Craft("iron_chestplate"), next(NONE, Map.of(), List.of("iron_chestplate", "leather_chestplate", "iron_helmet")));
        assertEquals(new Craft("leather_chestplate"), next(NONE, Map.of(), List.of("leather_chestplate", "leather_helmet")));
        // chest worn already: the next slot down is filled
        assertEquals(new Craft("iron_leggings"), next(Map.of(Slot.CHEST, "iron_chestplate"), Map.of(), List.of("iron_leggings", "iron_helmet")));
    }

    @Test
    void doesNotCraftWhatWouldNotBeAnUpgrade() {
        Map<Slot, String> worn = Map.of(Slot.CHEST, "iron_chestplate", Slot.LEGS, "iron_leggings", Slot.HEAD, "iron_helmet", Slot.FEET, "iron_boots");
        assertNull(next(worn, Map.of(), List.of("leather_helmet", "golden_boots", "iron_boots")));
    }

    @Test
    void skippedItemsAreNotTriedAgain() {
        GearPlan.Job job = GearPlan.next(NONE, Map.of(), GearPlan.AUTO,
                id -> id.equals("iron_chestplate") || id.equals("leather_chestplate"), GearPlan::tableScore, Set.of("iron_chestplate"));
        assertEquals(new Craft("leather_chestplate"), job);
    }

    @Test
    void explicitMaterialRestrictsCrafting() {
        GearPlan.Job job = GearPlan.next(NONE, Map.of(), List.of("leather"), id -> true, GearPlan::tableScore, Set.of());
        assertEquals(new Craft("leather_chestplate"), job);
    }

    @Test
    void wornFromIgnoresNonArmor() {
        Map<Slot, String> worn = GearPlan.wornFrom(List.of("iron_helmet", "shield", "leather_boots"));
        assertEquals(Map.of(Slot.HEAD, "iron_helmet", Slot.FEET, "leather_boots"), worn);
    }

    @Test
    void menuCraftTargetsPreferEmptySlotsThenBestMaterialAndAreCapped() {
        List<String> all = List.of("leather_helmet", "iron_helmet", "diamond_helmet", "leather_chestplate", "iron_chestplate",
                "diamond_chestplate", "leather_leggings", "iron_leggings", "diamond_leggings", "leather_boots", "iron_boots", "diamond_boots");
        List<String> targets = GearPlan.armorCraftTargets(Map.of(Slot.HEAD, "iron_helmet"), all, GearPlan::tableScore, 6);
        assertEquals(6, targets.size());
        assertEquals(List.of("diamond_chestplate", "diamond_leggings", "diamond_boots", "iron_chestplate", "iron_leggings", "iron_boots"), targets);
        // an upgrade to a worn slot comes after the empty ones; worse and equal pieces are left out
        List<String> upgrade = GearPlan.armorCraftTargets(Map.of(Slot.HEAD, "iron_helmet", Slot.FEET, "iron_boots"),
                List.of("leather_helmet", "diamond_helmet", "iron_boots", "leather_leggings"), GearPlan::tableScore, 6);
        assertEquals(List.of("leather_leggings", "diamond_helmet"), upgrade);
    }

    @Test
    void weaponRanksOrderMaterialThenSwordOverAxe() {
        assertTrue(GearPlan.weaponRank("iron_sword") > GearPlan.weaponRank("stone_sword"));
        assertTrue(GearPlan.weaponRank("iron_sword") > GearPlan.weaponRank("iron_axe"));
        assertTrue(GearPlan.weaponRank("diamond_axe") > GearPlan.weaponRank("iron_sword"));
        assertEquals(0, GearPlan.weaponRank("iron_pickaxe"));
    }
}
