package com.aiciv.stronghold;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TriangulateTest {
    @Test
    void crossingOfTwoRays() {
        double[] p = Triangulate.intersect(0, 0, 45, 100, 0, 315).orElseThrow();
        assertEquals(50, p[0], 1e-6);
        assertEquals(-50, p[1], 1e-6);
    }

    @Test
    void parallelRaysDoNotCross() {
        assertTrue(Triangulate.intersect(0, 0, 10, 100, 0, 12).isEmpty());
    }

    @Test
    void aCrossingBehindEitherThrowerIsRejected() {
        assertTrue(Triangulate.intersect(0, 0, 45, 100, 0, 135).isEmpty());
        assertTrue(Triangulate.intersect(0, 0, 225, 100, 0, 315).isEmpty());
    }

    @Test
    void bearingsAreClockwiseFromNorth() {
        assertEquals(90, Triangulate.bearing(1, 0), 1e-9);
        assertEquals(0, Triangulate.bearing(0, -1), 1e-9);
        assertEquals(180, Triangulate.bearing(0, 1), 1e-9);
        assertEquals(270, Triangulate.bearing(-1, 0), 1e-9);
        assertEquals(315, Triangulate.bearing(-1, -1), 1e-9);
    }

    @Test
    void roundTripFindsTheTarget() {
        double tx = 1200, tz = -800;
        double b1 = Triangulate.bearing(tx - 10, tz - 20), b2 = Triangulate.bearing(tx - 400, tz - 400);
        Optional<double[]> p = Triangulate.intersect(10, 20, b1, 400, 400, b2);
        assertEquals(tx, p.orElseThrow()[0], 1e-6);
        assertEquals(tz, p.orElseThrow()[1], 1e-6);
    }
}
