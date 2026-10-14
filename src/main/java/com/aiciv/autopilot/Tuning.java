package com.aiciv.autopilot;

/** How a personality plays: retries, how far it explores, when it retreats, how much it keeps, how often it chats. */
public record Tuning(int maxRetries, int exploreDistance, int retreatHealth, double hysteresis, boolean homeAtNight,
                     int keepCount, boolean helpsWhenAsked, double chatCooldownFactor, int depositWhenFree) {
    public static final Tuning DEFAULT = new Tuning(3, 48, 8, 0.15, false, 16, true, 1.0, 0);
}
