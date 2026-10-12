package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TemplatesTest {
    private static Blueprint make(String t, int size) throws BlueprintException {
        return Templates.make(t, size, "cobblestone");
    }

    @Test
    void defaultBlockCounts() throws Exception {
        assertEquals(71, make("hut", Templates.defaultSize("hut")).placeCount());
        assertEquals(58, make("tower", 3).placeCount());
        assertEquals(24, make("wall", Templates.defaultSize("wall")).placeCount());
        assertEquals(20, make("bridge", 4).placeCount());
        assertEquals(24, make("farm_plot", Templates.defaultSize("farm_plot")).placeCount());
    }

    @Test
    void defaultSizes() {
        assertEquals(5, Templates.defaultSize("hut"));
        assertEquals(4, Templates.defaultSize("tower"));
        assertEquals(8, Templates.defaultSize("wall"));
        assertEquals(6, Templates.defaultSize("bridge"));
        assertEquals(5, Templates.defaultSize("farm_plot"));
    }

    @Test
    void doorGapCellsAreAir() throws Exception {
        Blueprint hut = make("hut", 5);
        assertEquals('.', hut.cells()[0][0][2]);
        assertEquals('.', hut.cells()[1][0][2]);
        assertEquals('m', hut.cells()[2][0][2]);
        Blueprint tower = make("tower", 3);
        assertEquals('.', tower.cells()[0][0][1]);
        assertEquals('.', tower.cells()[1][0][1]);
    }

    @Test
    void everyTemplateAtItsMaxSizeFitsTheLimit() throws Exception {
        for (String t : Templates.NAMES) {
            Blueprint bp = make(t, 99);
            assertTrue(bp.width() <= 16 && bp.depth() <= 16 && bp.height() <= 16, t + " is " + bp.width() + "x" + bp.depth() + "x" + bp.height());
        }
    }

    @Test
    void sizesClamp() throws Exception {
        assertEquals(make("hut", 9).width(), make("hut", 99).width());
        assertEquals(make("hut", 5).width(), make("hut", 1).width());
        assertEquals(16, make("wall", 99).width());
    }

    @Test
    void bridgeSitsOneBelowTheBuildPoint() throws Exception {
        assertEquals(-1, make("bridge", 4).yOffset());
        assertEquals(0, make("hut", 5).yOffset());
    }

    @Test
    void unknownTemplateAndBlankMaterialAreRejected() {
        BlueprintException e = assertThrows(BlueprintException.class, () -> Templates.make("castle", 5, "stone"));
        assertEquals("template must be one of hut, tower, wall, bridge, farm_plot", e.getMessage());
        assertThrows(BlueprintException.class, () -> Templates.make("hut", 5, " "));
    }

    @Test
    void materialIsNormalised() throws Exception {
        assertEquals("oak_planks", Templates.make("wall", 4, "Oak Planks").legend().get('m'));
    }
}
