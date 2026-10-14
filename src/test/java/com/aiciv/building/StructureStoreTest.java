package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureStoreTest {
    private static Structure s(String name, String dim, int x, int z) {
        return new Structure(name, "Ada", dim, new Pos(x, 64, z), new Pos(x, 64, z), new Pos(x + 4, 67, z + 4), "hut", 100);
    }

    @Test
    void duplicateNamesAreRejectedInAnyCase() {
        StructureStore store = new StructureStore();
        assertTrue(store.add(s("Hut_One", "minecraft:overworld", 0, 0)));
        assertFalse(store.add(s("hut_one", "minecraft:overworld", 9, 9)));
        assertTrue(store.get("HUT_ONE").isPresent());
        assertTrue(store.get("nope").isEmpty());
    }

    @Test
    void nearestFiltersDimensionAndOrdersByDistance() {
        StructureStore store = new StructureStore();
        store.add(s("far", "minecraft:overworld", 100, 0));
        store.add(s("near", "minecraft:overworld", 5, 0));
        store.add(s("nether", "minecraft:the_nether", 1, 1));
        store.add(s("mid", "minecraft:overworld", 40, 0));
        List<Structure> out = store.nearest("minecraft:overworld", new Pos(0, 64, 0), 2);
        assertEquals(List.of("near", "mid"), out.stream().map(Structure::name).toList());
    }

    @Test
    void jsonRoundTripLoadsClean() {
        StructureStore store = new StructureStore();
        store.add(s("a", "minecraft:overworld", 1, 2));
        store.add(s("b", "minecraft:the_nether", -3, 4));
        assertTrue(store.dirty());
        StructureStore back = StructureStore.fromJson(store.toJson());
        assertFalse(back.dirty());
        assertEquals(store.get("a"), back.get("a"));
        assertEquals(store.get("b"), back.get("b"));
        store.markClean();
        assertFalse(store.dirty());
    }

    @Test
    void removeExactOnlyDropsThatVeryEntry() {
        StructureStore store = new StructureStore();
        store.add(s("Wall", "minecraft:overworld", 4, 8));
        Structure wall = store.get("Wall").orElseThrow();
        assertFalse(store.removeExact("wall", wall.owner(), wall.dimension(), wall.origin()), "another spelling");
        assertFalse(store.removeExact("Wall", "someone_else", wall.dimension(), wall.origin()), "another owner");
        assertFalse(store.removeExact("Wall", wall.owner(), "minecraft:the_nether", wall.origin()), "another dimension");
        assertFalse(store.removeExact("Wall", wall.owner(), wall.dimension(), new Pos(4, 65, 8)), "another place");
        assertTrue(store.get("Wall").isPresent());
        assertTrue(store.removeExact("Wall", wall.owner(), wall.dimension(), wall.origin()));
        assertTrue(store.get("Wall").isEmpty());
    }

    @Test
    void badEntriesAreSkipped() {
        StructureStore back = StructureStore.fromJson("[{\"name\":\"x\"},{\"nonsense\":1}]");
        assertTrue(back.get("x").isEmpty());
    }
}
