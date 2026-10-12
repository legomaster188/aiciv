package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ItemNamesTest {
    @Test
    void levenshteinCountsEdits() {
        assertEquals(3, ItemNames.levenshtein("kitten", "sitting"));
        assertEquals(5, ItemNames.levenshtein("", "stone"));
        assertEquals(5, ItemNames.levenshtein("stone", ""));
        assertEquals(0, ItemNames.levenshtein("dirt", "dirt"));
    }

    @Test
    void rankOrdersByDistanceThenAlphabetically() {
        List<String> ids = List.of("oak_planks", "oak_plank_stairs", "oak_log", "birch_planks", "oak_slab", "dirt");
        // oak_planks is 1 edit away; oak_log and oak_slab are both 4, so alphabetical; birch_planks is 6
        assertEquals(List.of("oak_planks", "oak_log", "oak_slab"), ItemNames.rank("oak_plnks", ids, 3));
        assertEquals(List.of("oak_log", "oak_slab"), ItemNames.rank("oak_lob", List.of("oak_slab", "oak_log"), 2));
        // equal distance: alphabetical
        assertEquals(List.of("bat", "cat"), ItemNames.rank("hat", List.of("cat", "bat"), 2));
        assertEquals(List.of("dirt"), ItemNames.rank("dirt", ids, 1));
    }
}
