package com.aiciv.bot;

/**
 * Whether one tick's movement is walking or falling, which adds to a bot's fall, or a jump to somewhere else
 * (teleport, portal, chorus fruit, ender pearl), which a real player's move packets never report as a fall.
 */
final class FallDelta {
    /** No player moves this far in one tick on its own; a change of this size is a teleport. */
    static final double MAX_STEP = 10.0;

    private FallDelta() {}

    static boolean counts(boolean sameLevel, double dx, double dy, double dz) {
        return sameLevel && Math.abs(dy) <= MAX_STEP && Math.sqrt(dx * dx + dz * dz) <= MAX_STEP;
    }
}
