package com.aiciv.perception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompassAndTimeTest {
    @Test
    void compassUsesMinecraftAxes() {
        assertEquals("N", Compass.direction(0, -10));
        assertEquals("E", Compass.direction(10, 0));
        assertEquals("S", Compass.direction(0, 10));
        assertEquals("W", Compass.direction(-10, 0));
        assertEquals("NE", Compass.direction(7, -7));
        assertEquals("SW", Compass.direction(-7, 7));
        assertEquals("here", Compass.direction(0.2, -0.3));
    }

    @Test
    void stampsAndPhases() {
        assertEquals("day 1, 06:00", GameTime.stamp(0));
        assertEquals("day 1, 12:00", GameTime.stamp(6000));
        assertEquals("day 1, 12:30", GameTime.stamp(6500));
        assertEquals("day 2, 00:00", GameTime.stamp(24000 + 18000));
        assertEquals(3, GameTime.day(48000));
        assertEquals("morning", GameTime.phase(3000));
        assertEquals("afternoon", GameTime.phase(24000 + 8000));
        assertEquals("night", GameTime.phase(15000));
    }
}
