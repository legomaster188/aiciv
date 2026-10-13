package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.world.PlacementStore.Placement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlacementStoreTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void beyondCapacityTheOldestInsertionIsEvicted() {
        PlacementStore store = new PlacementStore(3);
        store.put(OVERWORLD, 1L, new Placement("dirt", "Ada", 10));
        store.put(OVERWORLD, 2L, new Placement("dirt", "Ada", 11));
        store.put(OVERWORLD, 3L, new Placement("dirt", "Ada", 12));
        store.put(OVERWORLD, 1L, new Placement("stone", "Bob", 13)); // a re-put refreshes, so 2 is now the oldest
        store.put(OVERWORLD, 4L, new Placement("dirt", "Ada", 14));
        assertEquals(3, store.size());
        assertEquals(Optional.empty(), store.get(OVERWORLD, 2L));
        assertEquals(Optional.of(new Placement("stone", "Bob", 13)), store.get(OVERWORLD, 1L));
        assertTrue(store.get(OVERWORLD, 3L).isPresent());
        assertTrue(store.get(OVERWORLD, 4L).isPresent());
    }

    @Test
    void getAfterRemoveIsEmpty() {
        PlacementStore store = new PlacementStore(10);
        store.put(OVERWORLD, 7L, new Placement("oak_planks", "Ada", 5));
        store.remove(OVERWORLD, 7L);
        assertEquals(Optional.empty(), store.get(OVERWORLD, 7L));
        assertEquals(0, store.size());
    }

    @Test
    void dimensionsAreSeparate() {
        PlacementStore store = new PlacementStore(10);
        store.put(NETHER, 7L, new Placement("netherrack", "Ada", 5));
        assertEquals(Optional.empty(), store.get(OVERWORLD, 7L));
        assertTrue(store.get(NETHER, 7L).isPresent());
    }

    @Test
    void placedByFiltersByDimensionPlacerAndTick() {
        PlacementStore store = new PlacementStore(10);
        store.put(OVERWORLD, 1L, new Placement("dirt", "Ada", 100));
        store.put(OVERWORLD, 2L, new Placement("dirt", "Ada", 200));
        store.put(OVERWORLD, 3L, new Placement("dirt", "Bob", 300));
        store.put(NETHER, 4L, new Placement("dirt", "Ada", 400));
        assertEquals(List.of(2L), store.placedBy(OVERWORLD, "Ada", 150));
        assertEquals(List.of(1L, 2L), store.placedBy(OVERWORLD, "Ada", 0));
        assertEquals(List.of(4L), store.placedBy(NETHER, "Ada", 0));
        assertEquals(List.of(), store.placedBy(OVERWORLD, "Cy", 0));
    }

    @Test
    void jsonRoundTripKeepsEveryEntry() {
        PlacementStore store = new PlacementStore(10);
        store.put(OVERWORLD, -123456789L, new Placement("dirt", "Ada", 100));
        store.put(OVERWORLD, 2L, new Placement("oak_door", "Bob", 200));
        store.put(NETHER, 3L, new Placement("netherrack", "Ada", 300));
        PlacementStore back = PlacementStore.fromJson(store.toJson(), 10);
        assertEquals(3, back.size());
        assertEquals(Optional.of(new Placement("dirt", "Ada", 100)), back.get(OVERWORLD, -123456789L));
        assertEquals(Optional.of(new Placement("oak_door", "Bob", 200)), back.get(OVERWORLD, 2L));
        assertEquals(Optional.of(new Placement("netherrack", "Ada", 300)), back.get(NETHER, 3L));
        assertFalse(back.dirty(), "a freshly loaded store has nothing to save");
    }

    @Test
    void loadingMoreThanTheCapacityKeepsTheNewest() {
        PlacementStore store = new PlacementStore(10);
        for (long i = 0; i < 5; i++) store.put(OVERWORLD, i, new Placement("dirt", "Ada", 100 + i));
        PlacementStore back = PlacementStore.fromJson(store.toJson(), 2);
        assertEquals(2, back.size());
        assertTrue(back.get(OVERWORLD, 3L).isPresent() && back.get(OVERWORLD, 4L).isPresent());
    }

    @Test
    void dirtyAfterPutAndCleanAfterMarkClean() {
        PlacementStore store = new PlacementStore(10);
        assertFalse(store.dirty());
        store.put(OVERWORLD, 1L, new Placement("dirt", "Ada", 1));
        assertTrue(store.dirty());
        store.markClean();
        assertFalse(store.dirty());
        store.remove(OVERWORLD, 1L);
        assertTrue(store.dirty());
    }
}
