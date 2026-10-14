package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnownBlocksTest {
    @Test
    void capacityKeepsTheNewestEntries() {
        KnownBlocks known = new KnownBlocks(128);
        for (int i = 0; i < 200; i++) known.see("iron_ore", new Pos(i, 10, 0), i);
        assertEquals(128, known.size());
        assertEquals(new Pos(72, 10, 0), known.nearest(l -> true, new Pos(0, 10, 0)).get(), "the 72 oldest are evicted");
        assertEquals(128, known.nearestN(new Pos(0, 10, 0), 1000, 0).size());
    }

    @Test
    void seeingAgainRefreshesInsteadOfDuplicating() {
        KnownBlocks known = new KnownBlocks(2);
        known.see("coal_ore", new Pos(1, 1, 1), 1);
        known.see("iron_ore", new Pos(2, 2, 2), 2);
        known.see("coal_ore", new Pos(1, 1, 1), 3); // refreshed: the iron ore is now the least recently seen
        known.see("gold_ore", new Pos(3, 3, 3), 4);
        assertEquals(2, known.size());
        assertTrue(known.nearest(l -> l.equals("coal_ore"), new Pos(0, 0, 0)).isPresent());
        assertTrue(known.nearest(l -> l.equals("iron_ore"), new Pos(0, 0, 0)).isEmpty());
    }

    @Test
    void goneRemovesTheEntry() {
        KnownBlocks known = new KnownBlocks(128);
        known.see("iron_ore", new Pos(5, 5, 5), 1);
        known.gone(new Pos(9, 9, 9)); // unknown positions are ignored
        assertEquals(1, known.size());
        known.gone(new Pos(5, 5, 5));
        assertEquals(0, known.size());
        assertTrue(known.nearest(l -> true, new Pos(0, 0, 0)).isEmpty());
    }

    @Test
    void nearestRespectsThePredicateAndDistance() {
        KnownBlocks known = new KnownBlocks(128);
        known.see("coal_ore", new Pos(2, 0, 0), 1);
        known.see("iron_ore", new Pos(10, 0, 0), 1);
        known.see("iron_ore", new Pos(-4, 0, 0), 1);
        Optional<Pos> iron = known.nearest(l -> l.endsWith("iron_ore"), new Pos(0, 0, 0));
        assertEquals(Optional.of(new Pos(-4, 0, 0)), iron);
        assertEquals(Optional.of(new Pos(10, 0, 0)), known.nearest(l -> l.endsWith("iron_ore"), new Pos(20, 0, 0)));
        assertEquals(Optional.empty(), known.nearest(l -> l.equals("diamond_ore"), new Pos(0, 0, 0)));
    }

    @Test
    void nearestNSkipsEntriesCloserThanMinDistanceAndSortsNearestFirst() {
        KnownBlocks known = new KnownBlocks(128);
        known.see("a", new Pos(5, 0, 0), 1);
        known.see("b", new Pos(30, 0, 0), 1);
        known.see("c", new Pos(0, 0, 25), 1);
        known.see("d", new Pos(100, 0, 0), 1);
        List<Map.Entry<Pos, String>> far = known.nearestN(new Pos(0, 0, 0), 2, 24);
        assertEquals(List.of("c", "b"), far.stream().map(Map.Entry::getValue).toList());
        assertEquals(new Pos(0, 0, 25), far.get(0).getKey());
    }

    @Test
    void aForestCannotPushOutOresOrFurnaces() {
        KnownBlocks known = new KnownBlocks(128);
        known.see("iron_ore", new Pos(0, 5, 0), 0);
        known.see("furnace", new Pos(1, 5, 0), 0);
        for (int i = 0; i < 200; i++) known.see("oak_log", new Pos(10 + i, 5, 0), i + 1);
        assertTrue(known.nearest(l -> l.equals("iron_ore"), new Pos(0, 5, 0)).isPresent());
        assertTrue(known.nearest(l -> l.equals("furnace"), new Pos(0, 5, 0)).isPresent());
        long logs = known.nearestN(new Pos(0, 5, 0), 1000, 0).stream().filter(e -> e.getValue().equals("oak_log")).count();
        assertEquals(24, logs);
        // The 24 newest logs survive.
        assertEquals(new Pos(10 + 176, 5, 0), known.nearest(l -> l.equals("oak_log"), new Pos(0, 5, 0)).get());
    }

    @Test
    void fullCacheStillEvictsTheOldestNonLogFirst() {
        KnownBlocks known = new KnownBlocks(3);
        known.see("iron_ore", new Pos(1, 0, 0), 1);
        known.see("coal_ore", new Pos(2, 0, 0), 2);
        known.see("oak_log", new Pos(3, 0, 0), 3);
        known.see("gold_ore", new Pos(4, 0, 0), 4);
        assertTrue(known.nearest(l -> l.equals("iron_ore"), new Pos(0, 0, 0)).isEmpty());
        assertEquals(3, known.size());
    }

    @Test
    void keepsEachDimension() {
        KnownBlocks known = new KnownBlocks(128);
        known.enterDimension("minecraft:overworld");
        known.see("iron_ore", new Pos(1, 1, 1), 1);
        known.enterDimension("minecraft:overworld");
        assertEquals(1, known.size());
        known.enterDimension("minecraft:the_nether");
        assertTrue(known.nearest(l -> true, new Pos(1, 1, 1)).isEmpty());
        assertEquals(0, known.size());
        known.see("nether_bricks", new Pos(5, 70, 5), 2);
        known.enterDimension("minecraft:overworld");
        assertEquals(new Pos(1, 1, 1), known.nearest(l -> l.equals("iron_ore"), new Pos(0, 0, 0)).orElseThrow());
        assertEquals(1, known.size());
        // A fourth dimension drops the least recently entered store: now the nether.
        known.enterDimension("minecraft:the_end");
        known.enterDimension("aiciv:fourth");
        known.enterDimension("minecraft:the_nether");
        assertEquals(0, known.size(), "the nether store was dropped");
        assertEquals(3, KnownBlocks.MAX_DIMENSIONS);
    }

    @Test
    void capsNetherBricks() {
        KnownBlocks known = new KnownBlocks(128);
        known.see("gold_ore", new Pos(0, 40, 0), 0);
        for (int i = 0; i < 12; i++) known.see("nether_bricks", new Pos(10 + i, 40, 0), i + 1);
        long bricks = known.nearestN(new Pos(0, 40, 0), 1000, 0).stream().filter(e -> e.getValue().equals("nether_bricks")).count();
        assertEquals(8, bricks);
        // The four oldest went first.
        assertEquals(new Pos(14, 40, 0), known.nearest(l -> l.equals("nether_bricks"), new Pos(0, 40, 0)).orElseThrow());
        assertTrue(known.nearest(l -> l.equals("gold_ore"), new Pos(0, 40, 0)).isPresent());
    }

    @Test
    void railsShareOneCap() {
        assertEquals("rail", KnownBlocks.group("powered_rail"));
        assertEquals("log", KnownBlocks.group("birch_log"));
        assertEquals(16, KnownBlocks.cap("obsidian"));
        KnownBlocks known = new KnownBlocks(128);
        for (int i = 0; i < 10; i++) known.see(i % 2 == 0 ? "rail" : "detector_rail", new Pos(i, 64, 0), i);
        assertEquals(8, known.size());
    }
}
