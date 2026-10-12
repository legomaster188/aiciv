package com.aiciv.reflex;

/** Decides which reflex, if any, a bot should react with right now. Pure, so every rule is unit-tested. */
public final class ReflexPolicy {
    public enum Reflex { NONE, ESCAPE_HAZARD, SURFACE, FLEE, FIGHT_BACK, EAT }

    static final float FLEE_HEALTH = 6.0F;
    static final int EAT_FOOD = 12;

    /**
     * @param busyEating something other than a reflex is already using an item (the brain's own eat action),
     *                   so a reflex meal would only get in its way
     */
    public record Snapshot(boolean inLavaOrFire, int air, int maxAir, float health, int food, boolean hasFood,
                           boolean recentlyAttackedByMob, boolean holdingWeaponOrTool, boolean busyEating) {}

    private ReflexPolicy() {}

    public static Reflex decide(Snapshot s) {
        if (s.inLavaOrFire()) return Reflex.ESCAPE_HAZARD;
        if (s.air() < s.maxAir() / 3) return Reflex.SURFACE;
        if (s.health() <= FLEE_HEALTH && s.recentlyAttackedByMob()) return Reflex.FLEE;
        if (s.recentlyAttackedByMob() && s.holdingWeaponOrTool()) return Reflex.FIGHT_BACK;
        if (s.food() <= EAT_FOOD && s.hasFood() && !s.busyEating()) return Reflex.EAT;
        return Reflex.NONE;
    }
}
