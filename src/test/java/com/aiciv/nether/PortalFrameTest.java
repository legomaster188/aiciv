package com.aiciv.nether;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.building.Blueprint;
import com.aiciv.building.Facing;
import com.aiciv.nav.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortalFrameTest {
    private static int count(Blueprint bp, char symbol) {
        int n = 0;
        for (int y = 0; y < bp.height(); y++) for (int r = 0; r < bp.depth(); r++) for (int c = 0; c < bp.width(); c++) if (bp.at(y, r, c) == symbol) n++;
        return n;
    }

    @Test
    void blueprintIsTheFourByFiveFrame() {
        Blueprint bp = PortalFrame.blueprint(true);
        assertEquals(4, bp.width());
        assertEquals(1, bp.depth());
        assertEquals(5, bp.height());
        assertEquals(10, count(bp, 'o'));
        assertEquals(4, count(bp, 'c'));
        assertEquals(6, count(bp, '.'));
        assertEquals("obsidian", bp.legend().get('o'));
        assertEquals("obsidian", bp.legend().get('c'));
        assertEquals("cobblestone", PortalFrame.blueprint(false).legend().get('c'));
        assertEquals("dirt", PortalFrame.blueprint("dirt").legend().get('c'));
    }

    @Test
    void frameCellsRunBottomRowThenColumnsThenTopRow() {
        List<Pos> cells = PortalFrame.frameCells(new Pos(0, 0, 0), Facing.SOUTH);
        assertEquals(new Pos(-1, 0, 0), cells.get(0));
        assertEquals(new Pos(-2, 0, 0), cells.get(1));
        assertEquals(new Pos(0, 1, 0), cells.get(2));
        assertEquals(new Pos(0, 3, 0), cells.get(4));
        assertEquals(new Pos(-3, 1, 0), cells.get(5));
        assertEquals(new Pos(-1, 4, 0), cells.get(8));
        assertEquals(new Pos(-2, 4, 0), cells.get(9));
        assertEquals(10, new HashSet<>(cells).size());
        for (Pos corner : PortalFrame.corners(new Pos(0, 0, 0), Facing.SOUTH)) assertFalse(cells.contains(corner), corner.toString());
        assertEquals(4, PortalFrame.corners(new Pos(0, 0, 0), Facing.SOUTH).size());
        assertEquals(6, PortalFrame.interior(new Pos(0, 0, 0), Facing.SOUTH).size());
    }

    @Test
    void framesFollowTheFacing() {
        List<Pos> east = PortalFrame.frameCells(new Pos(10, 5, 10), Facing.EAST);
        assertEquals(new Pos(10, 5, 11), east.get(0));
        assertEquals(new Pos(10, 5, 12), east.get(1));
        assertEquals(new Pos(10, 6, 10), east.get(2));
    }

    @Test
    void cornerBlockPrefersObsidianThenAThrowaway() {
        assertEquals("obsidian", PortalFrame.cornerBlock(Map.of("obsidian", 14)));
        assertEquals("dirt", PortalFrame.cornerBlock(Map.of("obsidian", 10, "dirt", 4)));
        assertNull(PortalFrame.cornerBlock(Map.of("obsidian", 10)));
        assertEquals("oak_planks", PortalFrame.cornerBlock(Map.of("obsidian", 10, "oak_planks", 5)));
        assertNull(PortalFrame.cornerBlock(Map.of("obsidian", 10, "dirt", 3)));
        assertEquals("cobblestone", PortalFrame.throwaway(Map.of("cobblestone", 4, "dirt", 9)));
    }

    @Test
    void theWallStandsBehindTheFrame() {
        List<Pos> wall = PortalFrame.wallCells(new Pos(0, 0, 0), Facing.SOUTH);
        assertEquals(20, wall.size());
        assertEquals(new Pos(0, 0, 1), wall.get(0));
        assertEquals(new Pos(-3, 4, 1), wall.get(19));
        assertEquals(20, PortalFrame.wallBlueprint("cobblestone").placeCount());
        assertEquals("cobblestone", PortalFrame.wallBlock(Map.of("cobblestone", 20, "dirt", 64)));
        assertEquals("dirt", PortalFrame.wallBlock(Map.of("cobblestone", 19, "dirt", 20)));
        assertNull(PortalFrame.wallBlock(Map.of("cobblestone", 19)));
    }
}
