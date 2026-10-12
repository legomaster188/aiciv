package com.aiciv.actions;

import java.util.regex.Pattern;

/** Cleans a player name the model typed, which often still carries the "[human]" tag the prompt showed. */
public final class PlayerNames {
    private static final Pattern TRAILING_TAG = Pattern.compile("\\s*(\\[[^\\]]*\\]|\\([^)]*\\))\\s*$");
    private static final Pattern WRAPPERS = Pattern.compile("^[\\s\"'`@]+|[\\s\"'`]+$");

    private PlayerNames() {}

    public static String clean(String raw) {
        String s = raw == null ? "" : raw;
        String previous;
        do {
            previous = s;
            s = WRAPPERS.matcher(s).replaceAll("");
            s = TRAILING_TAG.matcher(s).replaceFirst("");
        } while (!s.equals(previous));
        return s;
    }
}
