package com.aiciv.vehicles;

import net.minecraft.util.Mth;

/** Pure steering decisions for a vehicle that turns by key presses (a boat). */
public final class Steering {
    public enum Turn { LEFT, RIGHT, STRAIGHT }

    private Steering() {}

    /** Yaw grows clockwise, so a positive wrapped difference to the wanted yaw is a turn to the right. */
    public static Turn turn(float yaw, float desired, float deadband) {
        float diff = Mth.wrapDegrees(desired - yaw);
        if (Math.abs(diff) <= deadband) return Turn.STRAIGHT;
        return diff > 0 ? Turn.RIGHT : Turn.LEFT;
    }

    /** Forward only when the bow is within 60 degrees of the way to go; a boat turning on the spot would drift. */
    public static boolean throttle(float yaw, float desired) {
        return Math.abs(Mth.wrapDegrees(desired - yaw)) < 60f;
    }
}
