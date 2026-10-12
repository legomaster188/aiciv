package com.aiciv.utility;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What each potion needs. Pure: no game classes. */
public final class BrewTable {
    private static final Map<String, String> INGREDIENT = Map.of(
            "awkward", "nether_wart",
            "swiftness", "sugar",
            "strength", "blaze_powder",
            "healing", "glistering_melon_slice",
            "fire_resistance", "magma_cream",
            "night_vision", "golden_carrot",
            "water_breathing", "pufferfish",
            "regeneration", "ghast_tear",
            "leaping", "rabbit_foot");
    private static final String SLOW_FALLING = "phantom_membrane";

    private BrewTable() {}

    /** The ingredient that turns an awkward potion (water bottle for awkward itself) into this one. */
    public static Optional<String> ingredientFor(String potion) {
        if (potion == null) return Optional.empty();
        if (potion.equals("slow_falling")) return Optional.of(SLOW_FALLING);
        return Optional.ofNullable(INGREDIENT.get(potion));
    }

    /** Every potion except awkward itself is brewed from an awkward potion. */
    public static boolean needsAwkwardBase(String potion) {
        return !"awkward".equals(potion);
    }

    /** "potion of swiftness", "speed", "Fire Res" -> the table name; unknown text comes back cleaned up. */
    public static String normalise(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (s.startsWith("potion of ")) s = s.substring("potion of ".length());
        else if (s.startsWith("potion_of_")) s = s.substring("potion_of_".length());
        s = s.replaceAll("[\s-]+", "_");
        return switch (s) {
            case "speed", "swift" -> "swiftness";
            case "fire_res", "fire_resist", "fireresistance" -> "fire_resistance";
            case "heal", "health", "instant_health" -> "healing";
            case "regen" -> "regeneration";
            case "jump", "jump_boost", "leap" -> "leaping";
            case "nightvision" -> "night_vision";
            case "waterbreathing", "water_breath" -> "water_breathing";
            case "slowfalling", "slow_fall", "feather_falling" -> "slow_falling";
            case "str" -> "strength";
            default -> s;
        };
    }
}
