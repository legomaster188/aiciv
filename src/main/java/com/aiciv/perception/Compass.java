package com.aiciv.perception;

public final class Compass {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private Compass() {}

    /** Eight-way direction for an offset. North is -z, east is +x. */
    public static String direction(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "here";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        return NAMES[Math.floorMod(Math.round(angle / 45.0), 8)];
    }
}
