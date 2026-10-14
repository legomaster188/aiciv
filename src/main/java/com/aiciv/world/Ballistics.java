package com.aiciv.world;

import java.util.OptionalDouble;

/**
 * Aims projectiles the way vanilla flies them: each tick gravity, then drag, then the move. Pure, so a throw is aimed
 * by where the bot looks and nothing else. Elevations are degrees above the horizon; pitches are Minecraft's
 * (negative looks up).
 */
public final class Ballistics {
    public enum Kind {
        /** Snowballs, eggs, ender pearls and eyes. */
        THROWN(1.5, 0.03, 0f),
        /** Splash and lingering potions leave the hand 20 degrees above where the player looks. */
        POTION(0.5, 0.05, -20f),
        /** A fully drawn bow; a weaker draw scales the speed. */
        ARROW(3.0, 0.05, 0f);

        private final double speed;
        private final double gravity;
        private final float pitchOffset;

        Kind(double speed, double gravity, float pitchOffset) {
            this.speed = speed;
            this.gravity = gravity;
            this.pitchOffset = pitchOffset;
        }

        public double speed() {
            return speed;
        }

        public double gravity() {
            return gravity;
        }

        /** Added to the look pitch to get the launch pitch. */
        public float pitchOffset() {
            return pitchOffset;
        }
    }

    public static final double DRAG = 0.99;
    public static final int MAX_TICKS = 200;
    /** How close the path must pass to the target. */
    public static final double TOLERANCE = 0.5;
    private static final double MIN_ELEVATION = -60;
    private static final int BISECTIONS = 60;

    private Ballistics() {}

    /** {horizontal distance, height} after {@code ticks} ticks of flight. */
    public static double[] simulate(double speed, double gravity, double elevationDeg, int ticks) {
        double e = Math.toRadians(elevationDeg);
        double vx = speed * Math.cos(e), vy = speed * Math.sin(e), x = 0, y = 0;
        for (int t = 0; t < ticks; t++) {
            vy -= gravity;
            vx *= DRAG;
            vy *= DRAG;
            x += vx;
            y += vy;
        }
        return new double[] {x, y};
    }

    /**
     * The look pitch for the low arc whose path passes within {@link #TOLERANCE} of a target {@code horizontal} blocks
     * away and {@code dy} above the launch point; empty when the target is out of range.
     */
    public static OptionalDouble pitch(Kind kind, double speed, double horizontal, double dy) {
        double g = kind.gravity();
        double eMax = bestElevation(speed, g, dy);
        if (horizontal > rangeAt(speed, g, eMax, dy) + TOLERANCE) return OptionalDouble.empty();
        double lo = MIN_ELEVATION, hi = eMax;
        if (heightAt(speed, g, hi, horizontal) < dy - TOLERANCE) return OptionalDouble.empty();
        if (heightAt(speed, g, lo, horizontal) > dy) return OptionalDouble.empty(); // even the steepest throw down passes over
        for (int i = 0; i < BISECTIONS; i++) {
            double mid = (lo + hi) / 2;
            if (heightAt(speed, g, mid, horizontal) < dy) lo = mid;
            else hi = mid;
        }
        double e = (lo + hi) / 2;
        if (Math.abs(heightAt(speed, g, e, horizontal) - dy) > TOLERANCE) return OptionalDouble.empty();
        return OptionalDouble.of(-e - kind.pitchOffset());
    }

    /** How far away a target {@code dy} above the launch point can be reached at most. */
    public static double maxRange(Kind kind, double speed, double dy) {
        return rangeAt(speed, kind.gravity(), bestElevation(speed, kind.gravity(), dy), dy);
    }

    /** The elevation, in whole degrees, that reaches farthest at height {@code dy}. */
    private static double bestElevation(double speed, double gravity, double dy) {
        double best = 45, bestRange = -1;
        for (int e = (int) MIN_ELEVATION; e <= 89; e++) {
            double r = rangeAt(speed, gravity, e, dy);
            if (r > bestRange) {
                bestRange = r;
                best = e;
            }
        }
        return best;
    }

    /** The horizontal distance at which the path comes down through height {@code dy}; 0 when it never gets that high. */
    private static double rangeAt(double speed, double gravity, double elevationDeg, double dy) {
        double e = Math.toRadians(elevationDeg);
        double vx = speed * Math.cos(e), vy = speed * Math.sin(e), x = 0, y = 0;
        boolean above = y >= dy;
        for (int t = 0; t < MAX_TICKS; t++) {
            double px = x, py = y;
            vy -= gravity;
            vx *= DRAG;
            vy *= DRAG;
            x += vx;
            y += vy;
            if (y >= dy) above = true;
            else if (above && vy < 0) return px + (x - px) * (py - dy) / (py - y);
        }
        return above ? x : 0;
    }

    /** The height of the path where it is {@code horizontal} away; minus infinity when it never gets that far. */
    private static double heightAt(double speed, double gravity, double elevationDeg, double horizontal) {
        double e = Math.toRadians(elevationDeg);
        double vx = speed * Math.cos(e), vy = speed * Math.sin(e), x = 0, y = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            double px = x, py = y;
            vy -= gravity;
            vx *= DRAG;
            vy *= DRAG;
            x += vx;
            y += vy;
            if (x >= horizontal) return py + (y - py) * (horizontal - px) / (x - px);
        }
        return Double.NEGATIVE_INFINITY;
    }
}
