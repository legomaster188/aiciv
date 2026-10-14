package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostsTest {
    @Test
    void woodenPickaxeNeedsATableLogUnlessOneIsNear() {
        assertEquals(Map.of("log", 3), Costs.forCraft("wooden_pickaxe", 1, sit().build()));
        assertEquals(Map.of("log", 2), Costs.forCraft("wooden_pickaxe", 1, sit().block("crafting_table", 3).build()));
    }

    @Test
    void stonePickaxeIsCobblestoneAndLogs() {
        assertEquals(Map.of("cobblestone", 3, "log", 2), Costs.forCraft("stone_pickaxe", 1, sit().build()));
        assertEquals(Map.of("cobblestone", 3, "log", 1), Costs.forCraft("stone_pickaxe", 1, sit().block("crafting_table", 2).build()));
        assertEquals(List.of("cobblestone", "log"), List.copyOf(Costs.forCraft("stone_pickaxe", 1, sit().build()).keySet()));
    }

    @Test
    void enderEyesTakeOneRodPerTwoAndOnePearlEach() {
        assertEquals(Map.of("blaze_rod", 6, "ender_pearl", 12), Costs.forCraft("ender_eye", 12, sit().build()));
        assertEquals(Map.of("blaze_rod", 1, "ender_pearl", 1), Costs.forCraft("ender_eye", 1, sit().build()));
        assertEquals(Map.of("blaze_rod", 2, "ender_pearl", 3), Costs.forCraft("ender_eye", 3, sit().build()));
    }

    @Test
    void planksCountAsLogsFourToOne() {
        var s = sit().inv("oak_log", 2).inv("birch_planks", 4).build();
        assertTrue(Costs.missing(Map.of("log", 3), s).isEmpty());
        assertEquals(Map.of("log", 1), Costs.missing(Map.of("log", 4), s));
    }

    @Test
    void haveSumsDeepslateCobbleAndAllWool() {
        var s = sit().inv("cobblestone", 2).inv("cobbled_deepslate", 3).inv("white_wool", 1).inv("red_wool", 2).build();
        assertEquals(5, Costs.have("cobblestone", s));
        assertEquals(3, Costs.have("wool", s));
        assertEquals(0, Costs.have("iron_ingot", s));
    }

    @Test
    void missingKeepsInsertionOrder() {
        var s = sit().build();
        assertEquals(List.of("cobblestone", "log"), List.copyOf(Costs.missing(Costs.forCraft("stone_sword", 1, s), s).keySet()));
    }

    @Test
    void tableFlagsMatchTheTable() {
        assertFalse(Costs.needsTable("crafting_table"));
        assertFalse(Costs.needsTable("ender_eye"));
        assertFalse(Costs.needsTable("flint_and_steel"));
        assertTrue(Costs.needsTable("iron_helmet"));
        assertTrue(Costs.needsTable("bread"));
        assertEquals(Map.of("iron_ingot", 8), Costs.raw("iron_chestplate"));
    }
}
