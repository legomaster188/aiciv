package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.Tuning;

/** How traits become play style. Pure. */
public final class Behaviour {
    private Behaviour() {}

    public static Tuning tuning(Traits t) {
        int maxRetries = clamp(3 + (int) Math.round(2 * t.patient()), 1, 5);
        int exploreDistance = 32 + (int) Math.round(32 * Math.max(0, t.brave()));
        int retreatHealth = clamp((int) Math.round(10 - 4 * t.brave()), 6, 14);
        double hysteresis = 0.15 * (1 + t.patient());
        boolean homeAtNight = t.tidy() >= 0.3 || t.brave() <= -0.3;
        int keepCount = t.greedy() <= -0.5 ? 0 : clamp((int) Math.round(16 + 32 * t.greedy()), 0, 64);
        boolean helpsWhenAsked = t.social() >= -0.5;
        double chatCooldownFactor = 1 - 0.5 * t.social();
        int depositWhenFree = t.tidy() >= 0.5 ? 9 : t.tidy() >= 0 ? 4 : 0;
        return new Tuning(maxRetries, exploreDistance, retreatHealth, hysteresis, homeAtNight, keepCount, helpsWhenAsked,
                chatCooldownFactor, depositWhenFree);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
