package com.aiciv.items;

import com.aiciv.utility.BrewTable;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Names of drinkable and throwable things. Pure: no game classes. */
public final class Potions {
    private static final Set<String> THROWABLE = Set.of("splash_potion", "lingering_potion", "snowball", "egg", "experience_bottle");

    /** What a model asked to throw: the item id and, for potions, the potion name (null for any). */
    public record ThrowSpec(String item, String potion) {}

    private Potions() {}

    /** "potion of healing" -> healing, "milk" -> milk_bucket, "honey" -> honey_bottle, "water" -> water; empty for blank. */
    public static Optional<String> normalise(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = BrewTable.normalise(raw);
        if (s.isEmpty()) return Optional.empty();
        return Optional.of(switch (s) {
            case "milk", "bucket_of_milk" -> "milk_bucket";
            case "honey" -> "honey_bottle";
            case "water_bottle" -> "water";
            default -> s;
        });
    }

    /** Item ids {@code throw} accepts. Ender pearls are not here: they have their own tool. */
    public static boolean isThrowable(String itemId) {
        return THROWABLE.contains(itemId);
    }

    /** "splash healing", "splash potion of healing", "snowball", "xp bottle" -> item id and potion name. */
    public static ThrowSpec throwSpec(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replaceAll("[\\s-]+", "_");
        for (String prefix : new String[] {"splash", "lingering"}) {
            if (!s.startsWith(prefix)) continue;
            String rest = s.substring(prefix.length());
            for (String noise : new String[] {"_potion_of_", "_potion_", "_of_", "_potion", "_"}) {
                if (rest.startsWith(noise)) {
                    rest = rest.substring(noise.length());
                    break;
                }
            }
            return new ThrowSpec(prefix + "_potion", normalise(rest).orElse(null));
        }
        if (s.equals("xp_bottle") || s.equals("exp_bottle") || s.equals("bottle_o_enchanting")) return new ThrowSpec("experience_bottle", null);
        return new ThrowSpec(s, null);
    }

    /** "strong_healing" and "long_swiftness" -> the base name. */
    public static String base(String potionKey) {
        if (potionKey == null) return null;
        if (potionKey.startsWith("long_")) return potionKey.substring(5);
        if (potionKey.startsWith("strong_")) return potionKey.substring(7);
        return potionKey;
    }

    /** Whether a potion with registry name {@code potionKey} counts as the wanted name (stronger and longer ones do). */
    public static boolean sameKind(String potionKey, String wanted) {
        return potionKey != null && wanted != null && base(potionKey).equals(wanted);
    }
}
