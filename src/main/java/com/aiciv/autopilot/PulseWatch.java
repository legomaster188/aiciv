package com.aiciv.autopilot;

/**
 * Turns pulses into urgent reasons, only on a state change and at most one per {@link #MIN_GAP_TICKS}, so a zombie circling
 * at the edge of range or damage over time cannot restart the bot's plan every second. Pure, one per bot.
 */
public final class PulseWatch {
    public static final int MIN_GAP_TICKS = 40;

    private Pulse prev;
    private String pending;
    private long lastUrgentTick = Long.MIN_VALUE / 2;

    /** The urgent reason, or null. The first pulse only records. A transition suppressed by the gap is kept and returned once the gap has passed. */
    public String check(Pulse now, Tuning t) {
        Pulse before = prev;
        prev = now;
        if (before == null) return null;
        String reason = transition(before, now, t);
        if (reason != null) pending = reason;
        if (pending == null || now.tick() - lastUrgentTick < MIN_GAP_TICKS) return null;
        String out = pending;
        pending = null;
        lastUrgentTick = now.tick();
        return out;
    }

    private static String transition(Pulse prev, Pulse now, Tuning t) {
        if (now.night() && !prev.night()) return "night fell";
        if (now.food() <= 6 && prev.food() > 6) return "hungry";
        int retreat = t.retreatHealth();
        if (now.health() <= retreat && now.hostileWithin8() != null
                && (prev.health() > retreat || prev.hostileWithin8() == null)) {
            return "health low";
        }
        if (now.hostileWithin8() != null && prev.hostileWithin8() == null) return now.hostileWithin8() + " nearby";
        return null;
    }
}
