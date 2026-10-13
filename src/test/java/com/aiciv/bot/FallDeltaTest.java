package com.aiciv.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FallDeltaTest {
    @Test
    void walkingAndFallingCount() {
        assertTrue(FallDelta.counts(true, 0.2, -0.4, 0.1));
        assertTrue(FallDelta.counts(true, 0, -3.9, 0)); // terminal velocity is about 3.9 blocks a tick
        assertTrue(FallDelta.counts(true, 6, -10, 6));
    }

    @Test
    void teleportsDoNot() {
        assertFalse(FallDelta.counts(true, 0, -20, 0), "20 blocks down in one tick");
        assertFalse(FallDelta.counts(true, 0, 20, 0), "20 blocks up in one tick");
        assertFalse(FallDelta.counts(true, 12, 0, 0), "12 blocks sideways in one tick");
        assertFalse(FallDelta.counts(false, 0, -1, 0), "into another dimension");
    }
}
