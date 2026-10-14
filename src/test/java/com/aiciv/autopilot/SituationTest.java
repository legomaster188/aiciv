package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SituationTest {
    @Test
    void logEquivalentsCountsFourPlanksAsALog() {
        Situation s = new TestSituation().inv("oak_log", 1).inv("crimson_stem", 1).inv("oak_planks", 5).inv("birch_planks", 4).build();
        assertEquals(2, s.logs());
        assertEquals(9, s.planks());
        assertEquals(4, s.logEquivalents());
    }

    @Test
    void countLikeMatchesExactOrPartialIds() {
        Situation s = new TestSituation().inv("oak_log", 2).inv("birch_log", 3).inv("bread", 4).build();
        assertEquals(4, s.countLike("bread"));
        assertEquals(5, s.countLike("logs"));
        assertEquals(0, s.count("logs"));
    }

    @Test
    void toolTiersTakeTheBestToolCarried() {
        Situation s = new TestSituation().inv("stone_pickaxe", 1).inv("wooden_pickaxe", 1).build();
        assertEquals(2, s.toolTier("pickaxe"));
        assertEquals(0, s.toolTier("axe"));
        assertEquals(0, Situation.toolTierOf("plastic"));
        assertEquals(5, Situation.toolTierOf("netherite"));
    }

    @Test
    void aBetterHeldToolCountsAsHavingAWorseOne() {
        Situation s = new TestSituation().hold("iron_sword").build();
        assertTrue(s.hasTool("wooden_sword"));
        assertTrue(s.hasTool("iron_sword"));
        assertFalse(s.hasTool("diamond_sword"));
        assertFalse(s.hasTool("bread"));
    }

    @Test
    void wornArmorTiers() {
        Situation s = new TestSituation().wear("iron_helmet").wear("diamond_boots").wear("leather_leggings").build();
        assertEquals(3, s.wornTier("helmet"));
        assertEquals(0, s.wornTier("chestplate"));
        assertEquals(2, s.wornAtLeast(3));
    }

    @Test
    void foodItemsSumsEverythingEdible() {
        Situation s = new TestSituation().inv("bread", 3).inv("apple", 2).inv("stick", 9).build();
        assertEquals(5, s.foodItems());
        assertTrue(Situation.FOODS.contains("cooked_beef"));
        assertEquals(List.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato"), Situation.RAW_FOODS);
    }

    @Test
    void knowsWhatItSeesOrRemembers() {
        Situation s = new TestSituation().block("coal_ore", 10).block("crafting_table", 3)
                .remember("iron_ore at 120 34 -50 (60m NE)").build();
        assertTrue(s.knows("iron_ore"));
        assertTrue(s.knows("coal_ore"));
        assertFalse(s.knows("diamond_ore"));
        assertTrue(s.sees("coal_ore", 10));
        assertFalse(s.sees("coal_ore", 9));
        assertTrue(s.tableNear());
        assertFalse(s.furnaceNear());
        assertTrue(new TestSituation().inv("furnace", 1).build().furnaceNear());
    }

    @Test
    void hostilesAndMobs() {
        Situation s = new TestSituation().hostile("zombie", 6).build();
        assertEquals("zombie", s.nearestHostile(8).orElseThrow().label());
        assertTrue(s.nearestHostile(5).isEmpty());
        assertEquals(1, s.mobCount("zombie", 6));
        assertEquals(0, s.mobCount("cow", 30));
    }

    @Test
    void homeIsParsedFromTheStructuresSection() {
        Situation s = new TestSituation().structure("other_hut by Bob at 1 2 3 (5m N)")
                .structure("ada_home by Ada at 10 64 -3 (12m NE)").build();
        assertArrayEquals(new int[] {10, 64, -3}, s.home().orElseThrow());
        assertEquals(Math.sqrt(100 + 9), s.distanceTo(new int[] {10, 64, -3}), 1e-9);
        assertTrue(new TestSituation().build().home().isEmpty());
    }

    @Test
    void homeNameIsLowerCaseAndAtMost32Characters() {
        assertEquals("ada_home", Situation.homeName("Ada"));
        String cut = Situation.homeName("A_Very_Long_Bot_Name_X_And_Then_Some");
        assertEquals(32, cut.length());
        assertEquals(cut, cut.toLowerCase());
        assertTrue(Situation.homeName("A_Very_Long_Bot_Name_X").length() <= 32);
    }

    @Test
    void actionsAreMatchedNormalised() {
        Situation s = new TestSituation().actions("placechest", "goto").build();
        assertTrue(s.hasAction("place_chest"));
        assertTrue(s.hasAction("goTo"));
        assertFalse(s.hasAction("build_portal"));
    }

    @Test
    void playersAreSplitIntoHumansAndAi() {
        Situation s = new TestSituation().player("Steve [human]", 10).player("Rex [AI]", 5).player("Far [human]", 40).build();
        assertEquals(List.of("Steve"), s.humans());
        assertEquals(List.of("Rex"), s.aiPlayers());
    }

    @Test
    void dimensionsFlagsTagsAndLastDone() {
        TestSituation t = new TestSituation().tick(5000);
        t.dimension = "the_nether";
        t.flags.add("planted");
        t.lastDone.put("farm/breed", 4000L);
        t.tags.add("villager:farmer");
        t.tags.add("crop_ripe");
        Situation s = t.build();
        assertTrue(s.inNether());
        assertFalse(s.inOverworld());
        assertFalse(s.inEnd());
        assertTrue(s.flag("planted"));
        assertTrue(s.doneWithin("farm/breed", 1000));
        assertFalse(s.doneWithin("farm/breed", 999));
        assertFalse(s.doneWithin("farm/plant", 99999));
        assertTrue(s.hasTag("crop_ripe"));
        assertEquals(List.of("villager:farmer"), s.tagsWithPrefix("villager:"));
    }
}
