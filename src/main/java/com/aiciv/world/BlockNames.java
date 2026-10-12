package com.aiciv.world;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Turns what a model says ("logs", "iron", "Oak Planks") into a test over block ids. */
public final class BlockNames {
    private static final Set<String> LOG_WORDS = Set.of("log", "logs", "wood", "tree", "trees");
    /** Words that end in s but are not plurals, so normalise leaves them alone. */
    private static final Set<String> KEEP_S = Set.of("planks");

    private BlockNames() {}

    /** A tree trunk: any {@code _log}, or a nether {@code _stem} (not mushroom, melon or pumpkin stems). */
    public static boolean isLog(String id) {
        return id.endsWith("_log") || (id.endsWith("_stem") && (id.contains("crimson") || id.contains("warped")));
    }

    /** Matches block ids given without the {@code minecraft:} prefix. A blank or missing query matches nothing. */
    public static Predicate<String> matcher(String query) {
        String q = clean(query);
        if (q.isEmpty()) return id -> false;
        if (LOG_WORDS.contains(q)) return BlockNames::isLog;
        if (q.equals("stone")) return id -> id.equals("stone") || id.equals("cobblestone") || id.equals("deepslate");
        if (q.equals("planks")) return id -> id.endsWith("_planks");
        return id -> id.equals(q) || id.endsWith("_" + q) || id.contains(q);
    }

    /** Lower-case, no namespace, spaces to underscores, and a plural "s" dropped ("Oak Logs" -> "oak_log"). */
    public static String normalise(String raw) {
        String q = clean(raw);
        String lastWord = q.substring(q.lastIndexOf('_') + 1);
        boolean plural = q.endsWith("s") && !q.endsWith("ss") && !q.endsWith("us") && !q.endsWith("is") && !KEEP_S.contains(lastWord);
        return plural ? q.substring(0, q.length() - 1) : q;
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String q = raw.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        return q.replaceAll("\s+", "_");
    }
}
