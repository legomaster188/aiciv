package com.aiciv.autopilot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PulseWatchTest {
    private static final Tuning T = Tuning.DEFAULT; // retreatHealth 8

    private static Pulse p(long tick, boolean night, String hostile) {
        return new Pulse(20, 20, night, hostile, tick);
    }

    @Test
    void pulseFiresOncePerTransition() {
        PulseWatch w = new PulseWatch();
        assertNull(w.check(p(0, false, null), T));
        assertEquals("night fell", w.check(p(100, true, null), T));
        assertNull(w.check(p(200, true, null), T));
        assertEquals("zombie nearby", w.check(p(300, true, "zombie"), T));
        assertNull(w.check(p(310, true, "zombie"), T));
        assertNull(w.check(p(320, true, null), T));
        assertNull(w.check(p(330, true, "zombie"), T), "returning 30 ticks after the last urgent turn is held");
        assertNull(w.check(p(335, true, "zombie"), T));
        assertEquals("zombie nearby", w.check(p(340, true, "zombie"), T), "and returned once 40 ticks have passed");
        assertNull(w.check(p(360, true, "zombie"), T));
    }

    @Test
    void theFirstPulseOnlyRecords() {
        PulseWatch w = new PulseWatch();
        assertNull(w.check(new Pulse(3, 2, true, "zombie", 0), T));
        assertNull(w.check(new Pulse(3, 2, true, "zombie", 100), T));
    }

    @Test
    void hungerAndLowHealth() {
        PulseWatch w = new PulseWatch();
        assertNull(w.check(new Pulse(20, 10, false, null, 0), T));
        assertEquals("hungry", w.check(new Pulse(20, 6, false, null, 100), T));
        assertNull(w.check(new Pulse(20, 5, false, null, 200), T));
        assertEquals("zombie nearby", w.check(new Pulse(20, 5, false, "zombie", 300), T));
        assertEquals("health low", w.check(new Pulse(7, 5, false, "zombie", 400), T));
        assertNull(w.check(new Pulse(6, 5, false, "zombie", 500), T));
    }

    @Test
    void lowHealthWhenAHostileArrivesWinsOverNearby() {
        PulseWatch w = new PulseWatch();
        assertNull(w.check(new Pulse(7, 20, false, null, 0), T));
        assertEquals("health low", w.check(new Pulse(7, 20, false, "skeleton", 100), T));
    }
}
