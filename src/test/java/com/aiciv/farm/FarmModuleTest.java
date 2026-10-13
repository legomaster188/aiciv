package com.aiciv.farm;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FarmModuleTest {
    private final FarmModule module = new FarmModule();

    private static ActionFactory.Result create(String name, String key, String value) {
        JsonObject args = new JsonObject();
        if (key != null) args.addProperty(key, value);
        return ActionFactory.create(ToolCall.of(name, args));
    }

    private static SnapshotSection tags(String... tags) {
        return new SnapshotSection("Crops", List.of(), null, List.of(tags));
    }

    private List<MenuOption> options(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory));
    }

    private static MenuOption find(List<MenuOption> options, String id) {
        return options.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void plantWithoutSeedIsInvalidNamingTheArgument() {
        ActionFactory.Result r = create("plant", null, null);
        assertTrue(assertInstanceOf(ActionFactory.Result.Invalid.class, r).reason().contains("\"seed\""), r.toString());
    }

    @Test
    void breedWithoutAnimalIsInvalidNamingTheArgument() {
        ActionFactory.Result r = create("breed", null, null);
        assertTrue(assertInstanceOf(ActionFactory.Result.Invalid.class, r).reason().contains("\"animal\""), r.toString());
    }

    @Test
    void argumentlessToolsAreValid() {
        for (String name : List.of("till", "harvest", "fish")) assertInstanceOf(ActionFactory.Result.Ok.class, create(name, null, null), name);
        assertInstanceOf(ActionFactory.Result.Ok.class, create("plant", "seed", "wheat_seeds"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("breed", "animal", "cow"));
    }

    @Test
    void breedCowNeedsTwoCowsAndTwoWheat() {
        SnapshotData two = Samples.snapshot(List.of(), List.of(new Sighting("cow", 2, 3, "N")), List.of());
        SnapshotData one = Samples.snapshot(List.of(), List.of(new Sighting("cow", 1, 3, "N")), List.of());
        assertNotNull(find(options(two, Map.of("wheat", 2)), "breed_cow"));
        assertNull(find(options(one, Map.of("wheat", 2)), "breed_cow"));
        assertNull(find(options(two, Map.of("wheat", 1)), "breed_cow"));
        assertNull(find(options(two, Map.of("carrot", 4)), "breed_cow"));
        ToolCall call = find(options(two, Map.of("wheat", 2)), "breed_cow").calls().get(0);
        assertEquals("breed", call.name());
        assertEquals("cow", call.args().get("animal").getAsString());
    }

    @Test
    void cookBeefUsesSmelt() {
        SnapshotData snap = Samples.snapshot(List.of(), List.of());
        MenuOption cook = find(options(snap, Map.of("beef", 12)), "cook_beef");
        assertNotNull(cook);
        ToolCall call = cook.calls().get(0);
        assertEquals("smelt", call.name());
        assertEquals("beef", call.args().get("item").getAsString());
        assertEquals(8, call.args().get("count").getAsInt());
    }

    @Test
    void cropAndFarmlandOptionsFollowTheSectionTags() {
        SnapshotData ripe = Samples.snapshot(List.of(), List.of(tags("crop_ripe", "farmland_free")));
        List<MenuOption> options = options(ripe, Map.of("wheat_seeds", 20, "wooden_hoe", 1));
        assertNotNull(find(options, "harvest_crops"));
        MenuOption plant = find(options, "plant_wheat_seeds");
        assertNotNull(plant);
        assertEquals(8, plant.calls().get(0).args().get("count").getAsInt());
        assertNull(find(options, "till_soil"), "free farmland already exists");

        SnapshotData bare = Samples.snapshot(List.of(), List.of());
        assertNotNull(find(options(bare, Map.of("wheat_seeds", 3, "wooden_hoe", 1)), "till_soil"));
        assertNull(find(options(bare, Map.of("wheat_seeds", 3)), "till_soil"), "needs a hoe");
        assertNull(find(options(bare, Map.of("wooden_hoe", 1)), "till_soil"), "needs a seed");
        assertNull(find(options(bare, Map.of("wheat_seeds", 3)), "harvest_crops"));
    }

    @Test
    void fishNeedsARodAndWater() {
        SnapshotData near = Samples.snapshot(List.of(), List.of(tags("water_near")));
        SnapshotData seen = Samples.snapshot(List.of(new Sighting("water", 40, 6, "E")), List.of());
        SnapshotData far = Samples.snapshot(List.of(new Sighting("water", 40, 12, "E")), List.of());
        assertNotNull(find(options(near, Map.of("fishing_rod", 1)), "fish"));
        assertNotNull(find(options(seen, Map.of("fishing_rod", 1)), "fish"));
        assertNull(find(options(far, Map.of("fishing_rod", 1)), "fish"));
        assertNull(find(options(near, Map.of()), "fish"));
    }
}
