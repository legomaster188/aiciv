package com.aiciv.stronghold;

import java.util.Optional;

/**
 * Where two eye flights cross. Bearings are degrees clockwise from north, so a bearing {@code b} points along
 * {@code (sin b, -cos b)} in (x, z).
 */
public final class Triangulate {
    /** Lines closer to parallel than this cross too far away (or not at all) to mean anything. */
    private static final double MIN_SIN = Math.sin(Math.toRadians(5));

    private Triangulate() {}

    /** {x, z} where the two rays cross; empty when they are nearly parallel or the crossing lies behind either thrower. */
    public static Optional<double[]> intersect(double x1, double z1, double b1, double x2, double z2, double b2) {
        double d1x = Math.sin(Math.toRadians(b1)), d1z = -Math.cos(Math.toRadians(b1));
        double d2x = Math.sin(Math.toRadians(b2)), d2z = -Math.cos(Math.toRadians(b2));
        double cross = d1x * d2z - d1z * d2x;
        if (Math.abs(cross) < MIN_SIN) return Optional.empty();
        double dx = x2 - x1, dz = z2 - z1;
        double t = (dx * d2z - dz * d2x) / cross;
        double s = (dx * d1z - dz * d1x) / cross;
        if (t < 0 || s < 0) return Optional.empty();
        return Optional.of(new double[] {x1 + t * d1x, z1 + t * d1z});
    }

    /** Degrees clockwise from north, in [0, 360), of the offset (dx, dz). */
    public static double bearing(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        return (degrees % 360 + 360) % 360;
    }
}
