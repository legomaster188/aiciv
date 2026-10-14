package com.aiciv.animals;

import java.util.List;
import java.util.Optional;

/** How each tameable animal is tamed, for messages and menus only; actions ask the entity and use the real interaction. Pure. */
public final class Tameables {
    public enum Method { FEED, RIDE }

    private Tameables() {}

    /** FEED animals take their item by hand; RIDE animals have to be ridden until they accept the rider. */
    public static Optional<Method> method(String animal) {
        if (animal == null) return Optional.empty();
        return switch (animal) {
            case "wolf", "cat", "parrot" -> Optional.of(Method.FEED);
            case "horse", "donkey", "mule", "llama", "trader_llama" -> Optional.of(Method.RIDE);
            default -> Optional.empty();
        };
    }

    /** The item ids that tame a FEED animal; empty for the rest. */
    public static List<String> items(String animal) {
        if (animal == null) return List.of();
        return switch (animal) {
            case "wolf" -> List.of("bone");
            case "cat" -> List.of("cod", "salmon");
            case "parrot" -> List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod");
            default -> List.of();
        };
    }

    /** "wolves", "cats": the plural for messages. */
    public static String plural(String animal) {
        return animal.endsWith("f") ? animal.substring(0, animal.length() - 1) + "ves" : animal + "s";
    }
}
