package com.aiciv.farm;

import java.util.Map;
import java.util.Optional;

/** Which seed item grows which crop block (ids without namespace). Pure. */
public final class Crops {
    private static final Map<String, String> CROP_FOR_SEED = Map.of(
            "wheat_seeds", "wheat",
            "beetroot_seeds", "beetroots",
            "carrot", "carrots",
            "potato", "potatoes",
            "pumpkin_seeds", "pumpkin_stem",
            "melon_seeds", "melon_stem",
            "torchflower_seeds", "torchflower_crop");

    private Crops() {}

    public static Optional<String> cropFor(String seedItem) {
        return Optional.ofNullable(CROP_FOR_SEED.get(seedItem));
    }

    public static Optional<String> seedFor(String cropBlock) {
        return CROP_FOR_SEED.entrySet().stream().filter(e -> e.getValue().equals(cropBlock)).map(Map.Entry::getKey).findFirst();
    }

    public static boolean isSeed(String item) {
        return CROP_FOR_SEED.containsKey(item);
    }
}
