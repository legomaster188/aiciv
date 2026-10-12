package com.aiciv.farm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CropsTest {
    @Test
    void seedsMapToCropsAndBack() {
        assertEquals(Optional.of("wheat"), Crops.cropFor("wheat_seeds"));
        assertEquals(Optional.of("carrots"), Crops.cropFor("carrot"));
        assertEquals(Optional.of("pumpkin_stem"), Crops.cropFor("pumpkin_seeds"));
        assertEquals(Optional.of("wheat_seeds"), Crops.seedFor("wheat"));
        assertEquals(Optional.of("potato"), Crops.seedFor("potatoes"));
        assertEquals(Optional.of("torchflower_seeds"), Crops.seedFor("torchflower_crop"));
        assertEquals(Optional.empty(), Crops.seedFor("dirt"));
    }

    @Test
    void everySeedRoundTrips() {
        for (String seed : new String[] {"wheat_seeds", "beetroot_seeds", "carrot", "potato", "pumpkin_seeds", "melon_seeds", "torchflower_seeds"}) {
            assertTrue(Crops.isSeed(seed), seed);
            assertEquals(Optional.of(seed), Crops.seedFor(Crops.cropFor(seed).orElseThrow()));
        }
    }

    @Test
    void dirtIsNotASeed() {
        assertFalse(Crops.isSeed("dirt"));
        assertEquals(Optional.empty(), Crops.cropFor("dirt"));
    }
}
