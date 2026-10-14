package com.aiciv.nether;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FortressSearchTest {
    @Test
    void spiralTurnsClockwiseWithGrowingLegs() {
        List<int[]> legs = FortressSearch.spiral(0, 0, "E", 64, 384);
        assertEquals(4, legs.size());
        assertArrayEquals(new int[] {64, 0}, legs.get(0));
        assertArrayEquals(new int[] {64, 64}, legs.get(1));
        assertArrayEquals(new int[] {-64, 64}, legs.get(2));
        assertArrayEquals(new int[] {-64, -64}, legs.get(3));
    }

    @Test
    void theLastLegIsCutToTheBudget() {
        List<int[]> legs = FortressSearch.spiral(0, 0, "E", 64, 400);
        assertEquals(5, legs.size());
        assertArrayEquals(new int[] {-48, -64}, legs.get(4));
        List<int[]> north = FortressSearch.spiral(10, 10, "N", 64, 100);
        assertArrayEquals(new int[] {10, -54}, north.get(0));
        assertArrayEquals(new int[] {46, -54}, north.get(1));
    }

    @Test
    void spiralStartsFromAnyDirection() {
        assertArrayEquals(new int[] {-64, 0}, FortressSearch.spiral(0, 0, "W", 64, 64).get(0));
        assertArrayEquals(new int[] {0, 64}, FortressSearch.spiral(0, 0, "S", 64, 64).get(0));
        assertTrue(FortressSearch.spiral(0, 0, "E", 64, 0).isEmpty());
    }

    @Test
    void recognisesFortressBlocks() {
        assertTrue(FortressSearch.isFortressBlock("nether_brick_fence"));
        assertTrue(FortressSearch.isFortressBlock("nether_bricks"));
        assertFalse(FortressSearch.isFortressBlock("netherrack"));
        assertFalse(FortressSearch.isFortressBlock("red_nether_bricks"));
    }
}
