package com.aiciv.nav;

import java.util.Locale;

/** Be within radius of a point horizontally, at any height. Used for legs of long trips. */
public record ColumnGoal(double x, double z, double radius) implements Goal {
    @Override
    public boolean reached(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    @Override
    public double estimate(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - radius);
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "column %.0f %.0f", Math.floor(x), Math.floor(z));
    }
}
