package com.aiciv.brain;

import java.util.regex.Pattern;

public final class Hearing {
    private Hearing() {}

    /** True when text names the player as a whole word, ignoring case. */
    public static boolean mentions(String text, String name) {
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        return p.matcher(text).find();
    }

    public static boolean hears(double distanceSq, int range, boolean mentioned) {
        return mentioned || distanceSq <= (double) range * range;
    }
}
