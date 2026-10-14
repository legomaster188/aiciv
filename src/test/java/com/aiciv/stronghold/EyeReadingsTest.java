package com.aiciv.stronghold;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.stronghold.EyeReadings.Reading;
import org.junit.jupiter.api.Test;

class EyeReadingsTest {
    @Test
    void keepsTheNewestFour() {
        EyeReadings r = new EyeReadings();
        for (int i = 0; i < 6; i++) r.add(new Reading(i, 0, 90, false, i));
        assertEquals(4, r.all().size());
        assertEquals(2, r.all().get(0).x());
        assertEquals(5, r.all().get(3).x());
    }

    @Test
    void noEstimateFromOneReading() {
        EyeReadings r = new EyeReadings();
        r.add(new Reading(0, 0, 45, false, 1));
        assertTrue(r.estimate().isEmpty());
    }

    @Test
    void estimateUsesTheWidestPair() {
        EyeReadings r = new EyeReadings();
        // target (500, -500); the first two readings are nearly parallel and noisy, the third is wide
        r.add(new Reading(0, 0, 45, false, 1));
        r.add(new Reading(30, 0, 47, false, 2));
        r.add(new Reading(1000, 0, 315, false, 3));
        double[] p = r.estimate().orElseThrow();
        assertEquals(500, p[0], 1e-6);
        assertEquals(-500, p[1], 1e-6);
    }

    @Test
    void aSunkEyeWins() {
        EyeReadings r = new EyeReadings();
        r.add(new Reading(0, 0, 45, false, 1));
        r.add(new Reading(1000, 0, 315, false, 2));
        r.add(new Reading(480, -490, 0, true, 3));
        double[] p = r.estimate().orElseThrow();
        assertEquals(480, p[0], 1e-9);
        assertEquals(-490, p[1], 1e-9);
    }
}
