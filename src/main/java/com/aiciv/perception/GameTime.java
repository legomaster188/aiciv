package com.aiciv.perception;

import java.util.Locale;

/** Overworld clock ticks to human time. Tick 0 is 06:00 on day 1. */
public final class GameTime {
    private GameTime() {}

    public static String stamp(long clockTicks) {
        long t = Math.floorMod(clockTicks, 24000L);
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "day %d, %02d:%02d", day(clockTicks), hours, minutes);
    }

    public static long day(long clockTicks) {
        return Math.floorDiv(clockTicks, 24000L) + 1;
    }

    public static String phase(long clockTicks) {
        long t = Math.floorMod(clockTicks, 24000L);
        if (t < 1000) return "early morning";
        if (t < 6000) return "morning";
        if (t < 11000) return "afternoon";
        if (t < 13000) return "evening";
        if (t < 23000) return "night";
        return "dawn";
    }
}
