package com.aiciv.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChestMemoryTest {
    @Test
    void capacityDropsTheLeastRecentlySeen() {
        ChestMemory memory = new ChestMemory();
        for (int i = 0; i < 16; i++) memory.seen(new Pos(i, 64, 0), Map.of("dirt", 1), i);
        memory.seen(new Pos(0, 64, 0), Map.of("dirt", 2), 100); // seen again: no longer the oldest
        memory.seen(new Pos(50, 64, 0), Map.of("dirt", 1), 101);
        assertTrue(memory.contents(new Pos(0, 64, 0)).isPresent());
        assertTrue(memory.contents(new Pos(1, 64, 0)).isEmpty());
        assertEquals(16, memory.positions().size());
    }

    @Test
    void describeFormatsNearestFirstWithTopFourKinds() {
        ChestMemory memory = new ChestMemory();
        Map<String, Integer> contents = new LinkedHashMap<>();
        contents.put("cobblestone", 40);
        contents.put("oak_log", 12);
        contents.put("dirt", 5);
        contents.put("sand", 4);
        contents.put("gravel", 2);
        contents.put("flint", 1);
        memory.seen(new Pos(4, 64, 0), contents, 1);
        memory.seen(new Pos(40, 64, 0), Map.of(), 2);
        List<String> lines = memory.describe(new Pos(0, 64, 0), 4);
        assertEquals("chest at 4 64 0 (4m E): cobblestone x40, oak_log x12, dirt x5, sand x4 (+2 more kinds)", lines.get(0));
        assertEquals("chest at 40 64 0 (40m E): empty", lines.get(1));
        assertEquals(1, memory.describe(new Pos(0, 64, 0), 1).size());
        assertEquals(List.of("cobblestone", "oak_log", "dirt", "sand", "gravel", "flint"), memory.nearestKinds(new Pos(0, 64, 0)));
    }

    @Test
    void forgetRemovesAChest() {
        ChestMemory memory = new ChestMemory();
        memory.seen(new Pos(1, 2, 3), Map.of("dirt", 1), 1);
        memory.forget(new Pos(1, 2, 3));
        assertTrue(memory.contents(new Pos(1, 2, 3)).isEmpty());
        assertTrue(memory.describe(new Pos(0, 0, 0), 4).isEmpty());
    }

    @Test
    void nearestIsTheChestNearestKindsDescribes() {
        ChestMemory memory = new ChestMemory();
        memory.seen(new Pos(9, 64, 0), Map.of("dirt", 1), 1);
        memory.seen(new Pos(-3, 64, 0), Map.of("sand", 1), 2);
        assertEquals(new Pos(-3, 64, 0), memory.nearest(new Pos(0, 64, 0)).orElseThrow());
        assertEquals(List.of("sand"), memory.nearestKinds(new Pos(0, 64, 0)));
        assertTrue(new ChestMemory().nearest(new Pos(0, 64, 0)).isEmpty());
    }

    @Test
    void changingDimensionForgetsEveryChest() {
        ChestMemory memory = new ChestMemory();
        memory.enterDimension("minecraft:overworld");
        memory.seen(new Pos(1, 64, 0), Map.of("dirt", 1), 1);
        memory.enterDimension("minecraft:overworld");
        assertEquals(1, memory.positions().size());
        memory.enterDimension("minecraft:the_nether");
        assertTrue(memory.positions().isEmpty());
    }
}
