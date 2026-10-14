package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class BallisticsTest {
    /** The height of the path where it reaches {@code horizontal}, launched at elevation {@code e} (degrees, up positive). */
    private static double heightAt(Ballistics.Kind kind, double speed, double e, double horizontal) {
        double[] prev = {0, 0};
        for (int t = 1; t <= Ballistics.MAX_TICKS; t++) {
            double[] now = Ballistics.simulate(speed, kind.gravity(), e, t);
            if (now[0] >= horizontal) {
                double f = (horizontal - prev[0]) / (now[0] - prev[0]);
                return prev[1] + f * (now[1] - prev[1]);
            }
            prev = now;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static void hits(Ballistics.Kind kind, double horizontal, double dy) {
        OptionalDouble look = Ballistics.pitch(kind, kind.speed(), horizontal, dy);
        assertTrue(look.isPresent(), kind + " to " + horizontal + ", " + dy);
        double launchPitch = look.getAsDouble() + kind.pitchOffset();
        double y = heightAt(kind, kind.speed(), -launchPitch, horizontal);
        assertEquals(dy, y, Ballistics.TOLERANCE, kind + " to " + horizontal + ", " + dy + " at look pitch " + look.getAsDouble());
    }

    @Test
    void thrownAndPotionPathsPassThroughTheTarget() {
        hits(Ballistics.Kind.THROWN, 10, 0);
        hits(Ballistics.Kind.THROWN, 25, 3);
        // A potion leaves at 0.5 blocks a tick (vanilla), so it reaches about 4.2 blocks on the level: (6, 0) cannot be hit.
        hits(Ballistics.Kind.POTION, 4, 0);
        hits(Ballistics.Kind.POTION, 5, -1.5);
        assertTrue(Ballistics.pitch(Ballistics.Kind.POTION, 0.5, 6, 0).isEmpty());
        hits(Ballistics.Kind.ARROW, 30, -2);
    }

    @Test
    void outOfRangeIsEmpty() {
        assertTrue(Ballistics.pitch(Ballistics.Kind.THROWN, 1.5, 200, 0).isEmpty());
    }

    @Test
    void maxRangeOfAThrowIsRealistic() {
        double range = Ballistics.maxRange(Ballistics.Kind.THROWN, 1.5, 0);
        assertTrue(range > 40 && range < 70, "range " + range);
        assertTrue(Ballistics.maxRange(Ballistics.Kind.THROWN, 1.5, 10) < range, "a higher target is reached less far");
    }

    @Test
    void aPotionIsThrownTwentyDegreesAboveTheLook() {
        double look = Ballistics.pitch(Ballistics.Kind.POTION, 0.5, 4, 0).orElseThrow();
        double launch = look + Ballistics.Kind.POTION.pitchOffset();
        assertEquals(launch + 20, look, 1e-9);
    }

    @Test
    void oneTickOfSimulation() {
        double[] r = Ballistics.simulate(1.5, 0.03, 0, 1);
        assertEquals(1.485, r[0], 1e-9);
        assertEquals(-0.0297, r[1], 1e-9);
    }
}
