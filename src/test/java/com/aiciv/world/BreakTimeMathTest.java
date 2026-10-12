package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BreakTimeMathTest {
    @Test
    void matchesVanillaBreakTimes() {
        assertEquals(7.5, BreakTime.seconds(1.5F, 1.0F, false), 1e-6);
        assertEquals(1.125, BreakTime.seconds(1.5F, 2.0F, true), 1e-6);
        assertEquals(3.0, BreakTime.seconds(2.0F, 1.0F, true), 1e-6);
    }

    @Test
    void unbreakableIsInfiniteAndInstantBlocksTakeOneTick() {
        assertEquals(Double.POSITIVE_INFINITY, BreakTime.seconds(-1.0F, 1.0F, false));
        assertEquals(0.05, BreakTime.seconds(0.0F, 1.0F, true), 1e-9);
    }
}
