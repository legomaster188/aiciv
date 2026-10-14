package com.aiciv.flight;

/** The flying rules of fly_to as a pure function: how steeply to point the nose and when to fire a rocket. */
public final class ElytraPilot {
    /** Ticks between two rockets: a rocket burns about two seconds. */
    public static final int ROCKET_GAP = 40;
    public static final int MAX_ROCKETS = 16;
    /** Closer than this to the target (horizontally) the pilot dives to land. */
    static final double LANDING_RADIUS = 24;
    /** Rocket again when the flight is lower than this above where it started... */
    static final double CLIMB_BAND = 20;
    /** ...or slower than this (blocks per tick). */
    static final double MIN_SPEED = 0.8;
    static final double GROUND_CLEARANCE = 6;
    /** Nose a little down: a glide of about ten blocks forward for each one lost. */
    public static final float GLIDE_PITCH = 10f;

    /** {@code dx}/{@code dz}: from the bot to the target. {@code groundY}: the top of the terrain under the bot. */
    public record State(double dx, double dz, double y, double groundY, double speed, int rockets, int sinceRocket, double startY) {}

    /** {@code pitch} in degrees, positive looking down. */
    public record Command(float pitch, boolean rocket, boolean land) {}

    private ElytraPilot() {}

    public static Command next(State s) {
        double horizontal = Math.hypot(s.dx(), s.dz());
        if (horizontal < LANDING_RADIUS) return new Command(35f, false, true);
        if (s.rockets() > 0 && s.sinceRocket() >= ROCKET_GAP && (s.y() < s.startY() + CLIMB_BAND || s.speed() < MIN_SPEED)) {
            return new Command(-20f, true, false);
        }
        if (s.y() - s.groundY() < GROUND_CLEARANCE && s.rockets() == 0) return new Command(0f, false, false);
        return new Command(GLIDE_PITCH, false, false);
    }
}
