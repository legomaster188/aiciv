package com.aiciv.nav;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.testutil.GridView;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathSearchTest {
    static NearGoal at(int x, int y, int z) {
        return new NearGoal(x + 0.5, y, z + 0.5, 0.5);
    }

    static PathSearch run(GridView g, Pos start, Goal goal) {
        PathSearch s = new PathSearch(g, start, goal, PathSearch.MAX_NODES);
        s.advance(Integer.MAX_VALUE);
        return s;
    }

    static PathSearch run(GridView g, Pos start, Goal goal, SearchOptions options) {
        PathSearch s = new PathSearch(g, start, goal, PathSearch.MAX_NODES, options);
        s.advance(Integer.MAX_VALUE);
        return s;
    }

    static GridView wall(double seconds) {
        GridView g = new GridView().floor(0, 6, 0, 0, 2);
        for (int z = 0; z <= 2; z++) g.breakable(3, 1, z, seconds).breakable(3, 2, z, seconds);
        return g;
    }

    @Test
    void tunnelsThroughOneBlockWallWhenAllowed() {
        PathSearch s = run(wall(0.5), new Pos(0, 1, 1), at(5, 1, 1), new SearchOptions(true, false));
        assertEquals(PathSearch.State.FOUND, s.state());
        List<Pos> broken = s.steps().stream().flatMap(st -> st.breaks().stream()).toList();
        assertFalse(broken.isEmpty());
        assertTrue(broken.stream().allMatch(p -> p.x() == 3 && p.y() >= 1 && p.y() <= 2), broken.toString());
        assertEquals(PathSearch.State.FAILED, run(wall(0.5), new Pos(0, 1, 1), at(5, 1, 1)).state());
    }

    @Test
    void walkStepBreaksFeetAndHead() {
        GridView g = new GridView().floor(0, 3, 0, 0, 0).breakable(2, 1, 0, 0.5).breakable(2, 2, 0, 0.5)
                .solid(2, 3, 0).solid(1, 3, 0).solid(0, 3, 0).solid(3, 3, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(3, 1, 0), new SearchOptions(true, false));
        assertEquals(PathSearch.State.FOUND, s.state());
        PathStep step = s.steps().stream().filter(st -> st.to().x() == 2).findFirst().orElseThrow();
        assertEquals(Move.WALK, step.move());
        assertEquals(List.of(new Pos(2, 1, 0), new Pos(2, 2, 0)), step.breaks());
    }

    @Test
    void prefersWalkingAroundOverSlowDigging() {
        GridView g = new GridView().floor(0, 6, 0, 0, 5);
        for (int z = 0; z <= 2; z++) g.breakable(3, 1, z, 10).breakable(3, 2, z, 10);
        PathSearch s = run(g, new Pos(0, 1, 1), at(5, 1, 1), new SearchOptions(true, false));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(s.steps().stream().allMatch(st -> st.breaks().isEmpty()), s.steps().toString());
        assertTrue(s.path().stream().anyMatch(p -> p.x() == 3 && p.z() >= 3), s.path().toString());
    }

    @Test
    void bridgesAOneBlockGap() {
        GridView g = new GridView().floor(0, 2, 0, 0, 0).floor(4, 6, 0, 0, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(6, 1, 0), new SearchOptions(false, true));
        assertEquals(PathSearch.State.FOUND, s.state());
        List<PathStep> bridges = s.steps().stream().filter(st -> st.move() == Move.BRIDGE).toList();
        assertEquals(1, bridges.size());
        assertEquals(new Pos(3, 0, 0), bridges.get(0).place());
        assertEquals(PathSearch.State.FAILED, run(g, new Pos(0, 1, 0), at(6, 1, 0)).state());
    }

    @Test
    void pillarsUpToALedge() {
        GridView g = new GridView().floor(0, 0, 0, 0, 0);
        for (int y = 1; y <= 4; y++) g.solid(-1, y, 0).solid(0, y, 1).solid(0, y, -1);
        for (int y = 1; y <= 3; y++) g.solid(1, y, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(1, 4, 0), new SearchOptions(false, true));
        assertEquals(PathSearch.State.FOUND, s.state());
        List<PathStep> pillars = s.steps().stream().filter(st -> st.move() == Move.PILLAR).toList();
        assertEquals(2, pillars.size());
        assertEquals(new Pos(0, 1, 0), pillars.get(0).place());
        assertEquals(new Pos(0, 2, 0), pillars.get(1).place());
    }

    @Test
    void swimsAcrossALake() {
        GridView g = new GridView().floor(0, 2, 0, 0, 0).floor(8, 10, 0, 0, 0);
        for (int x = 3; x <= 7; x++) g.addWater(x, 1, 0).addWater(x, 2, 0);
        for (SearchOptions o : List.of(SearchOptions.WALK_ONLY, new SearchOptions(true, true))) {
            PathSearch s = run(g, new Pos(0, 1, 0), at(10, 1, 0), o);
            assertEquals(PathSearch.State.FOUND, s.state());
            assertTrue(s.steps().stream().anyMatch(st -> st.move() == Move.SWIM), s.steps().toString());
        }
    }

    @Test
    void neverDigsNextToLava() {
        // One-wide corridor: both wall cells have lava beside them, so digging would open a lava pocket.
        GridView g = new GridView().floor(0, 6, 0, 1, 1)
                .breakable(3, 1, 1, 0.5).breakable(3, 2, 1, 0.5).lava(3, 1, 2).lava(3, 2, 2);
        PathSearch s = run(g, new Pos(0, 1, 1), at(5, 1, 1), new SearchOptions(true, false));
        assertEquals(PathSearch.State.FAILED, s.state());
        GridView safe = new GridView().floor(0, 6, 0, 1, 1).breakable(3, 1, 1, 0.5).breakable(3, 2, 1, 0.5);
        assertEquals(PathSearch.State.FOUND, run(safe, new Pos(0, 1, 1), at(5, 1, 1), new SearchOptions(true, false)).state());
    }

    @Test
    void neverBridgesNextToLava() {
        GridView g = new GridView().floor(0, 2, 0, 0, 0).floor(4, 6, 0, 0, 0).lava(3, -1, 0)
                .floor(0, 6, 3, 0, 0); // ceiling: no pillaring up and bridging higher up
        for (int x = -1; x <= 7; x++) g.solid(x, 1, 1).solid(x, 2, 1).solid(x, 1, -1).solid(x, 2, -1);
        g.solid(-1, 1, 0).solid(-1, 2, 0).solid(7, 1, 0).solid(7, 2, 0);
        assertEquals(PathSearch.State.FAILED, run(g, new Pos(0, 1, 0), at(6, 1, 0), new SearchOptions(false, true)).state());
    }

    @Test
    void neverPillarsNextToLava() {
        GridView g = new GridView().floor(0, 0, 0, 0, 0).lava(-1, 1, 0);
        for (int y = 2; y <= 4; y++) g.solid(-1, y, 0);
        for (int y = 1; y <= 4; y++) g.solid(0, y, 1).solid(0, y, -1);
        for (int y = 1; y <= 3; y++) g.solid(1, y, 0);
        assertEquals(PathSearch.State.FAILED, run(g, new Pos(0, 1, 0), at(1, 4, 0), new SearchOptions(false, true)).state());
    }

    @Test
    void walksAcrossAFlatFloor() {
        PathSearch s = run(new GridView().floor(0, 10, 0, 0, 0), new Pos(0, 1, 0), at(10, 1, 0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertEquals(10, s.path().size());
        assertEquals(new Pos(10, 1, 0), s.path().get(9));
    }

    @Test
    void diagonalsDoNotCutCorners() {
        GridView g = new GridView().floor(0, 2, 0, 0, 2).solid(1, 1, 0).solid(1, 2, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(1, 1, 1));
        assertEquals(List.of(new Pos(0, 1, 1), new Pos(1, 1, 1)), s.path());
    }

    @Test
    void jumpsUpOneBlock() {
        GridView g = new GridView().floor(0, 5, 0, 0, 0).floor(3, 5, 1, 0, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), at(5, 2, 0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(s.path().contains(new Pos(3, 2, 0)), s.path().toString());
    }

    @Test
    void cannotClimbATwoBlockWall() {
        GridView g = new GridView().floor(0, 6, 0, 0, 2);
        for (int z = 0; z <= 2; z++) g.solid(3, 1, z).solid(3, 2, z);
        PathSearch s = run(g, new Pos(0, 1, 1), at(5, 1, 1));
        assertEquals(PathSearch.State.FAILED, s.state());
        assertEquals("no path", s.failure());
    }

    @Test
    void dropsThreeButNotFour() {
        GridView ok = new GridView().floor(0, 2, 3, 0, 0).floor(3, 6, 0, 0, 0);
        assertEquals(PathSearch.State.FOUND, run(ok, new Pos(0, 4, 0), at(5, 1, 0)).state());
        GridView tooDeep = new GridView().floor(0, 2, 3, 0, 0).floor(3, 6, -1, 0, 0);
        assertEquals(PathSearch.State.FAILED, run(tooDeep, new Pos(0, 4, 0), at(5, 0, 0)).state());
    }

    @Test
    void goesAroundLavaAndMagma() {
        GridView g = new GridView().floor(0, 6, 0, 0, 2).lava(3, 0, 1).magma(4, 0, 1);
        PathSearch s = run(g, new Pos(0, 1, 1), at(6, 1, 1));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertFalse(s.path().contains(new Pos(3, 1, 1)));
        assertFalse(s.path().contains(new Pos(4, 1, 1)));
        assertTrue(s.path().stream().allMatch(p -> p.y() == 1), s.path().toString());
    }

    @Test
    void neverEntersLavaWhenADetourExists() {
        GridView g = new GridView().floor(0, 6, 0, 0, 2).lava(3, 1, 1);
        PathSearch s = run(g, new Pos(0, 1, 1), at(6, 1, 1));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertFalse(s.path().contains(new Pos(3, 1, 1)), s.path().toString());
    }

    @Test
    void magmaFloorInANarrowCorridorIsNoRoute() {
        GridView g = new GridView().floor(0, 6, 0, 1, 1).magma(3, 0, 1);
        PathSearch s = run(g, new Pos(0, 1, 1), at(6, 1, 1));
        assertEquals(PathSearch.State.FAILED, s.state());
        assertEquals("no path", s.failure());
    }

    @Test
    void unreachableGoalStopsAtTheNodeCap() {
        GridView g = new GridView().floor(-100, 100, 0, -100, 100);
        PathSearch s = new PathSearch(g, new Pos(0, 1, 0), at(0, 50, 0), 1000);
        s.advance(Integer.MAX_VALUE);
        assertEquals(PathSearch.State.FAILED, s.state());
        assertEquals("no path", s.failure());
        assertTrue(s.expandedCount() <= 1000);
    }

    @Test
    void advanceIsIncremental() {
        PathSearch s = new PathSearch(new GridView().floor(0, 50, 0, 0, 0), new Pos(0, 1, 0), at(50, 1, 0), PathSearch.MAX_NODES);
        assertEquals(PathSearch.State.RUNNING, s.advance(1));
        int calls = 1;
        while (s.advance(5) == PathSearch.State.RUNNING) calls++;
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(calls > 3);
    }

    @Test
    void columnGoalIgnoresHeight() {
        GridView g = new GridView().floor(0, 10, 0, 0, 0);
        PathSearch s = run(g, new Pos(0, 1, 0), new ColumnGoal(8.5, 0.5, 1.0));
        assertEquals(PathSearch.State.FOUND, s.state());
        assertTrue(s.path().get(s.path().size() - 1).x() >= 7);
    }

    @Test
    void cancelStopsTheSearch() {
        PathSearch s = new PathSearch(new GridView().floor(0, 50, 0, 0, 0), new Pos(0, 1, 0), at(50, 1, 0), PathSearch.MAX_NODES);
        s.cancel();
        assertEquals(PathSearch.State.FAILED, s.advance(100));
        assertEquals("cancelled", s.failure());
    }
}
