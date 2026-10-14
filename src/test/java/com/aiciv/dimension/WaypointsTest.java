package com.aiciv.dimension;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaypointsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void markingTheSamePlaceAgainKeepsOneWithTheNewerNote() {
        Waypoints w = new Waypoints();
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(0, 64, 0), "to the_nether", 1);
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(5, 64, 5), "rebuilt", 2);
        assertEquals(1, w.in(OVERWORLD).size());
        assertEquals("rebuilt", w.in(OVERWORLD).get(0).note());
        assertEquals(new Pos(5, 64, 5), w.in(OVERWORLD).get(0).pos());
        // Farther than 8 blocks, another kind or another dimension is a different waypoint.
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(20, 64, 5), "", 3);
        w.mark(Waypoints.FORTRESS, OVERWORLD, new Pos(5, 64, 5), "", 4);
        w.mark(Waypoints.PORTAL, NETHER, new Pos(5, 64, 5), "", 5);
        assertEquals(3, w.in(OVERWORLD).size());
        assertEquals(1, w.in(NETHER).size());
    }

    @Test
    void pastCapacityTheOldestGoes() {
        Waypoints w = new Waypoints();
        for (int i = 0; i <= Waypoints.CAPACITY; i++) w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(i * 100, 64, 0), "", i);
        List<Waypoints.Waypoint> all = w.in(OVERWORLD);
        assertEquals(Waypoints.CAPACITY, all.size());
        assertEquals(new Pos(100, 64, 0), all.get(0).pos(), "the first one marked is gone");
    }

    @Test
    void nearestFiltersByKindAndDimension() {
        Waypoints w = new Waypoints();
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(100, 64, 0), "", 1);
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(30, 64, 0), "", 2);
        w.mark(Waypoints.FORTRESS, OVERWORLD, new Pos(1, 64, 0), "", 3);
        w.mark(Waypoints.PORTAL, NETHER, new Pos(2, 64, 0), "", 4);
        assertEquals(new Pos(30, 64, 0), w.nearest(Waypoints.PORTAL, OVERWORLD, new Pos(0, 64, 0)).orElseThrow().pos());
        assertEquals(new Pos(2, 64, 0), w.nearest(Waypoints.PORTAL, NETHER, new Pos(0, 64, 0)).orElseThrow().pos());
        assertTrue(w.nearest(Waypoints.STRONGHOLD, OVERWORLD, new Pos(0, 64, 0)).isEmpty());
    }

    @Test
    void describeListsNearestFirstWithDistanceDirectionAndNote() {
        Waypoints w = new Waypoints();
        w.mark(Waypoints.FORTRESS, NETHER, new Pos(0, 64, 0), "", 1);
        w.mark(Waypoints.STRONGHOLD, OVERWORLD, new Pos(310, 30, 15), "", 2);
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(10, 64, 3), "to the_nether", 3);
        List<String> lines = w.describe(OVERWORLD, new Pos(10, 64, 15), 6);
        assertEquals(List.of("portal at 10 64 3 (12m N), to the_nether", "stronghold at 310 30 15 (302m E)"), lines);
        assertEquals(1, w.describe(OVERWORLD, new Pos(10, 64, 15), 1).size());
        assertTrue(w.describe("minecraft:the_end", new Pos(0, 64, 0), 6).isEmpty());
    }

    @Test
    void forgetRemovesTheSameKindNearby() {
        Waypoints w = new Waypoints();
        w.mark(Waypoints.PORTAL, OVERWORLD, new Pos(0, 64, 0), "", 1);
        w.mark(Waypoints.ARRIVAL, OVERWORLD, new Pos(0, 64, 0), "", 2);
        w.forget(Waypoints.PORTAL, OVERWORLD, new Pos(3, 64, 0));
        assertTrue(w.nearest(Waypoints.PORTAL, OVERWORLD, new Pos(0, 64, 0)).isEmpty());
        assertTrue(w.nearest(Waypoints.ARRIVAL, OVERWORLD, new Pos(0, 64, 0)).isPresent());
    }
}
