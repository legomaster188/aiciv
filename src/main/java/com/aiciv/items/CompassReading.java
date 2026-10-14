package com.aiciv.items;

import com.aiciv.perception.Compass;

/** The snapshot line for a compass. Pure. */
public final class CompassReading {
    private CompassReading() {}

    /**
     * {@code kind} is {@code spawn}, {@code lodestone} or {@code recovery}; dx and dz are the target minus the bot. Direction
     * only, as a real compass shows; null when the target is where the bot stands.
     */
    public static String line(String kind, int dx, int dz) {
        if (dx == 0 && dz == 0) return null;
        String dir = Compass.direction(dx, dz);
        return switch (kind) {
            case "lodestone" -> "compass points " + dir + " (lodestone)";
            case "recovery", "recovery_compass", "death" -> "recovery_compass points " + dir + " (where you died)";
            default -> "compass points " + dir + " (world spawn)";
        };
    }
}
