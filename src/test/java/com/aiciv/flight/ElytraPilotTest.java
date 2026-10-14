package com.aiciv.flight;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.flight.ElytraPilot.Command;
import com.aiciv.flight.ElytraPilot.State;
import org.junit.jupiter.api.Test;

class ElytraPilotTest {
    /** 100 blocks out, 60 above the ground, flying fast, with rockets ready and a long-ago boost. */
    private static State state(double dx, double dz, double y, double groundY, double speed, int rockets, int sinceRocket, double startY) {
        return new State(dx, dz, y, groundY, speed, rockets, sinceRocket, startY);
    }

    @Test
    void landsWhenCloseToTheTarget() {
        Command c = ElytraPilot.next(state(10, 10, 120, 64, 1.2, 4, 100, 100));
        assertEquals(35f, c.pitch());
        assertTrue(c.land());
        assertFalse(c.rocket());
        // 23.9 is still close; 24 is not.
        assertTrue(ElytraPilot.next(state(23.9, 0, 120, 64, 1.2, 4, 100, 100)).land());
        assertFalse(ElytraPilot.next(state(24, 0, 120, 64, 1.2, 0, 100, 100)).land());
    }

    @Test
    void firesARocketWhenLowAndOneIsReady() {
        Command c = ElytraPilot.next(state(100, 0, 105, 64, 1.5, 3, 40, 100));
        assertTrue(c.rocket());
        assertEquals(-20f, c.pitch());
        assertFalse(c.land());
    }

    @Test
    void firesARocketWhenSlowEvenIfHigh() {
        Command c = ElytraPilot.next(state(100, 0, 150, 64, 0.5, 3, 40, 100));
        assertTrue(c.rocket());
        assertEquals(-20f, c.pitch());
    }

    @Test
    void respectsTheGapBetweenRockets() {
        Command c = ElytraPilot.next(state(100, 0, 105, 64, 0.5, 3, ElytraPilot.ROCKET_GAP - 1, 100));
        assertFalse(c.rocket());
        assertEquals(10f, c.pitch());
    }

    @Test
    void doesNotWasteRocketsWhenHighAndFast() {
        Command c = ElytraPilot.next(state(100, 0, 150, 64, 1.5, 3, 100, 100));
        assertFalse(c.rocket());
        assertEquals(10f, c.pitch());
    }

    @Test
    void levelsOutNearTheGroundWithNoRockets() {
        assertEquals(0f, ElytraPilot.next(state(100, 0, 68, 64, 1.0, 0, 100, 100)).pitch());
        assertEquals(10f, ElytraPilot.next(state(100, 0, 80, 64, 1.0, 0, 100, 100)).pitch());
    }

    @Test
    void theLimitsAreTheContract() {
        assertEquals(40, ElytraPilot.ROCKET_GAP);
        assertEquals(16, ElytraPilot.MAX_ROCKETS);
    }
}
