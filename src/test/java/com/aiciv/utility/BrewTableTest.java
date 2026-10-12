package com.aiciv.utility;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BrewTableTest {
    @Test
    void everyListedPotionMapsToItsIngredient() {
        Map<String, String> expected = Map.of(
                "awkward", "nether_wart", "swiftness", "sugar", "strength", "blaze_powder",
                "healing", "glistering_melon_slice", "fire_resistance", "magma_cream", "night_vision", "golden_carrot",
                "water_breathing", "pufferfish", "regeneration", "ghast_tear", "leaping", "rabbit_foot");
        expected.forEach((potion, ingredient) -> assertEquals(ingredient, BrewTable.ingredientFor(potion).orElseThrow(), potion));
        assertEquals("phantom_membrane", BrewTable.ingredientFor("slow_falling").orElseThrow());
        assertTrue(BrewTable.ingredientFor("invisibility").isEmpty());
    }

    @Test
    void namesNormalise() {
        assertEquals("swiftness", BrewTable.normalise("speed"));
        assertEquals("swiftness", BrewTable.normalise("potion of swiftness"));
        assertEquals("fire_resistance", BrewTable.normalise("fire res"));
        assertEquals("night_vision", BrewTable.normalise("Night Vision"));
        assertEquals("healing", BrewTable.normalise("minecraft:healing"));
    }

    @Test
    void onlyAwkwardNeedsNoBase() {
        assertFalse(BrewTable.needsAwkwardBase("awkward"));
        assertTrue(BrewTable.needsAwkwardBase("swiftness"));
        assertTrue(BrewTable.needsAwkwardBase("slow_falling"));
    }
}
