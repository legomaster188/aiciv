package com.aiciv.farm;

import java.util.List;

/** What each farm animal eats, for menus and error messages only; actions ask {@code Animal.isFood}. Pure. */
public final class AnimalFood {
    private static final List<String> MEAT = List.of("beef", "cooked_beef", "porkchop", "cooked_porkchop", "chicken",
            "cooked_chicken", "mutton", "cooked_mutton", "rabbit", "cooked_rabbit", "rotten_flesh");

    private AnimalFood() {}

    /** The food item ids for an animal type id ("cow", "pig"...); empty for an animal not listed. */
    public static List<String> foods(String animal) {
        if (animal == null) return List.of();
        return switch (animal) {
            case "cow", "mooshroom", "sheep", "goat" -> List.of("wheat");
            case "pig" -> List.of("carrot", "potato", "beetroot");
            case "chicken" -> List.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds");
            case "rabbit" -> List.of("carrot", "dandelion");
            case "horse", "donkey" -> List.of("golden_carrot", "golden_apple");
            case "wolf" -> MEAT;
            case "cat" -> List.of("cod", "salmon");
            default -> List.of();
        };
    }
}
