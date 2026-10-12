package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class BlockNamesTest {
    @Test
    void logMatchesEveryLogAndStemButNotPlanks() {
        Predicate<String> log = BlockNames.matcher("log");
        assertTrue(log.test("oak_log"));
        assertTrue(log.test("birch_log"));
        assertTrue(log.test("crimson_stem"));
        assertFalse(log.test("oak_planks"));
        for (String alias : new String[] {"logs", "wood", "tree", "trees"}) {
            assertTrue(BlockNames.matcher(alias).test("spruce_log"), alias);
        }
    }

    @Test
    void oreNamesMatchPlainAndDeepslateVariants() {
        Predicate<String> iron = BlockNames.matcher("iron");
        assertTrue(iron.test("iron_ore"));
        assertTrue(iron.test("deepslate_iron_ore"));
        assertFalse(iron.test("coal_ore"));
        assertTrue(BlockNames.matcher("iron_ore").test("deepslate_iron_ore"));
        assertTrue(BlockNames.matcher("coal_ore").test("coal_ore"));
    }

    @Test
    void stoneFamilyAndPlanks() {
        Predicate<String> stone = BlockNames.matcher("stone");
        assertTrue(stone.test("stone"));
        assertTrue(stone.test("cobblestone"));
        assertTrue(stone.test("deepslate"));
        assertFalse(stone.test("dirt"));
        assertFalse(stone.test("stone_bricks"));
        assertTrue(BlockNames.matcher("planks").test("dark_oak_planks"));
        assertFalse(BlockNames.matcher("planks").test("oak_log"));
    }

    @Test
    void exactIdsAndNamespaceAreHandled() {
        Predicate<String> exact = BlockNames.matcher("minecraft:stone");
        assertTrue(exact.test("stone"));
        assertFalse(exact.test("dirt"));
        assertTrue(BlockNames.matcher("crafting_table").test("crafting_table"));
        assertTrue(BlockNames.matcher("table").test("crafting_table"));
        assertFalse(BlockNames.matcher("dirt").test("stone"));
    }

    @Test
    void normaliseCleansModelInput() {
        assertEquals("oak_log", BlockNames.normalise("Oak Logs"));
        assertEquals("stone", BlockNames.normalise("minecraft:stone"));
        assertEquals("iron_ore", BlockNames.normalise("  Iron ORE "));
        assertEquals("glass", BlockNames.normalise("glass"));
        assertEquals("planks", BlockNames.normalise("planks"));
        assertEquals("diamond_ore", BlockNames.normalise("diamond ores"));
    }

    @Test
    void blankOrMissingQueriesMatchNothing() {
        assertFalse(BlockNames.matcher("").test("stone"));
        assertFalse(BlockNames.matcher("   ").test("stone"));
        assertFalse(BlockNames.matcher(null).test("stone"));
        assertEquals("", BlockNames.normalise(null));
        assertEquals("", BlockNames.normalise("  "));
    }

    @Test
    void onlyNetherStemsCountAsLogs() {
        assertTrue(BlockNames.isLog("warped_stem"));
        assertTrue(BlockNames.isLog("stripped_crimson_stem"));
        assertFalse(BlockNames.isLog("mushroom_stem"));
        assertFalse(BlockNames.isLog("melon_stem"));
        assertFalse(BlockNames.matcher("log").test("mushroom_stem"));
    }
}
