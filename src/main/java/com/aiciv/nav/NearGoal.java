package com.aiciv.nav;

import java.util.Locale;

/** Be within radius of a point. Feet nodes are measured from their block centre. */
public record NearGoal(double x, double y, double z, double radius) implements Goal {
    @Override
    public boolean reached(Pos feet) {
        double dx = feet.x() + 0.5 - x, dy = feet.y() - y, dz = feet.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    @Override
    public double estimate(Pos feet) {
        double dx = feet.x() + 0.5 - x, dz = feet.z() + 0.5 - z;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) + 0.5 * Math.abs(feet.y() - y) - radius);
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", Math.floor(x), Math.floor(y), Math.floor(z));
    }
}
