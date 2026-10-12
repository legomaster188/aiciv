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
