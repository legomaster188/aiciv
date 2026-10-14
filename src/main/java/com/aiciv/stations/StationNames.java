package com.aiciv.stations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Pure name handling for the station actions: trim templates, trim materials, netherite results and banner patterns. */
public final class StationNames {
    private static final String TRIM_SUFFIX = "_armor_trim_smithing_template";
    private static final Set<String> TRIMS = Set.of("sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout",
            "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt");
    /** Items that carry a trim material, in the order they are offered. */
    static final List<String> TRIM_MATERIALS = List.of("iron_ingot", "gold_ingot", "copper_ingot", "diamond", "emerald",
            "lapis_lazuli", "quartz", "redstone", "amethyst_shard", "netherite_ingot", "resin_brick");
    private static final Set<String> NETHERITE_PIECES = Set.of("sword", "pickaxe", "axe", "shovel", "hoe", "spear", "helmet",
            "chestplate", "leggings", "boots");

    private StationNames() {}

    static String normalise(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", "_");
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /** "coast", "coast trim" or "coast_armor_trim_smithing_template" to the template's item id; empty when unknown. */
    public static Optional<String> trimTemplate(String raw) {
        String s = normalise(raw);
        if (s.endsWith(TRIM_SUFFIX)) s = s.substring(0, s.length() - TRIM_SUFFIX.length());
        else if (s.endsWith("_armor_trim")) s = s.substring(0, s.length() - "_armor_trim".length());
        else if (s.endsWith("_trim")) s = s.substring(0, s.length() - "_trim".length());
        return TRIMS.contains(s) ? Optional.of(s + TRIM_SUFFIX) : Optional.empty();
    }

    public static List<String> trimNames() {
        return TRIMS.stream().sorted().toList();
    }

    /** "iron", "iron ingot" or "iron_ingot" to the material item's id; empty when it is not a trim material. */
    public static Optional<String> trimMaterial(String raw) {
        String s = normalise(raw);
        if (s.isEmpty()) return Optional.empty();
        if (TRIM_MATERIALS.contains(s)) return Optional.of(s);
        for (String id : TRIM_MATERIALS) if (id.startsWith(s + "_")) return Optional.of(id);
        return Optional.empty();
    }

    /** {@code diamond_sword} to {@code netherite_sword}; empty for anything that is not a diamond tool or armor piece. */
    public static Optional<String> netheriteResult(String diamondItem) {
        String s = normalise(diamondItem);
        if (!s.startsWith("diamond_")) return Optional.empty();
        String piece = s.substring("diamond_".length());
        return NETHERITE_PIECES.contains(piece) ? Optional.of("netherite_" + piece) : Optional.empty();
    }

    /** Splits text into book pages of at most {@code pageLength} characters, breaking at a space or newline when there is one near the end. */
    public static List<String> pages(String text, int pageLength) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > pageLength) {
            int cut = pageLength;
            for (int i = pageLength; i > pageLength - pageLength / 8; i--) {
                if (Character.isWhitespace(rest.charAt(i - 1))) {
                    cut = i;
                    break;
                }
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        if (!rest.isEmpty() || out.isEmpty()) out.add(rest);
        return out;
    }

    /** Lower case, spaces as underscores, and the common spoken names mapped to the pattern's registry key. */
    public static String patternKey(String raw) {
        String s = normalise(raw);
        if (s.matches("(top|bottom|left|right|center|middle)_stripe")) {
            return "stripe_" + s.substring(0, s.indexOf('_'));
        }
        return s; // cross, border, creeper, flower, skull, globe, piglin, flow, guster and mojang are their own keys
    }
}
