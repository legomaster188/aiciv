package com.aiciv.vehicles;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SteeringTest {
    @Test
    void turnsTowardTheWantedYaw() {
        assertEquals(Steering.Turn.RIGHT, Steering.turn(0, 30, 10));
        assertEquals(Steering.Turn.LEFT, Steering.turn(0, -30, 10));
        assertEquals(Steering.Turn.STRAIGHT, Steering.turn(0, 5, 10));
    }

    @Test
    void turnsTheShortWayAcrossTheSeam() {
        assertEquals(Steering.Turn.RIGHT, Steering.turn(170, -170, 10));
        assertEquals(Steering.Turn.LEFT, Steering.turn(-170, 170, 10));
    }

    @Test
    void onlyPaddlesWhenTheBowIsRoughlyOnCourse() {
        assertFalse(Steering.throttle(0, 70));
        assertTrue(Steering.throttle(0, 50));
        assertTrue(Steering.throttle(170, -170));
    }
}
