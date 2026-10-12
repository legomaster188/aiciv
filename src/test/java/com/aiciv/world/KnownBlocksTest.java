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
}
