package com.aiciv.items;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemsModuleTest {
    private final ItemsModule module = new ItemsModule();

    private ActionFactory.Result create(String name, String json) {
        return ActionFactory.create(ToolCall.of(name, JsonParser.parseString(json).getAsJsonObject()));
    }

    private String invalid(String name, String json) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, create(name, json), name + " " + json).reason();
    }

    @Test
    void throwNeedsExactlyOneTarget() {
        assertTrue(invalid("throw", "{\"item\": \"snowball\", \"x\": 1, \"y\": 64, \"z\": 3, \"target\": \"zombie\"}").contains("not both"));
        assertTrue(invalid("throw", "{\"item\": \"snowball\"}").contains("x y z or a \"target\""));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("throw", "{\"item\": \"snowball\", \"target\": \"zombie\"}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("throw", "{\"item\": \"egg\", \"x\": 1, \"y\": 64, \"z\": 3}"));
    }

    @Test
    void fillBucketWantsWaterOrLava() {
        assertTrue(invalid("fill_bucket", "{\"fluid\": \"honey\"}").contains("water or lava"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("fillBucket", "{\"fluid\": \"Lava\"}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("fill_bucket", "{\"fluid\": \"water\", \"x\": 1, \"y\": 64, \"z\": 3}"));
    }

    @Test
    void missingArgumentsAreNamed() {
        assertTrue(invalid("empty_bucket", "{\"x\": 1, \"y\": 2}").contains("\"z\""));
        assertTrue(invalid("ignite", "{}").contains("\"x\""));
        assertTrue(invalid("drink", "{}").contains("\"item\""));
    }

    @Test
    void boneMealCountClamps() {
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("bone_meal", "{\"count\": 40}"));
        assertEquals("bone_meal 8", ok.action().describe());
        ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("bonemeal", "{}"));
        assertEquals("bone_meal 3", ok.action().describe());
    }

    private List<String> ids(SnapshotData snap, Map<String, Integer> inv) {
        return module.menuOptions(snap, Samples.context(inv)).stream().map(MenuOption::id).toList();
    }

    @Test
    void menusOfferWhatTheBotCanDo() {
        SnapshotData water = Samples.snapshot(List.of(new Sighting("water", 3, 3, "N")), List.of());
        assertTrue(ids(water, Map.of("bucket", 1)).contains("fill_water_bucket"));
        assertFalse(ids(water, Map.of()).contains("fill_water_bucket"));
        SnapshotData far = Samples.snapshot(List.of(new Sighting("water", 3, 20, "N")), List.of());
        assertFalse(ids(far, Map.of("bucket", 1)).contains("fill_water_bucket"));

        SnapshotSection crops = new SnapshotSection("Crops", List.of("wheat x6 growing (nearest 4m E)"), null, List.of());
        SnapshotData field = Samples.snapshot(List.of(), List.of(crops));
        assertTrue(ids(field, Map.of("bone_meal", 5)).contains("bone_meal_crops"));
        assertFalse(ids(field, Map.of()).contains("bone_meal_crops"));

        assertTrue(ids(Samples.snapshot(List.of(), List.of()), Map.of("map", 1)).contains("make_map"));

        SnapshotSection items = new SnapshotSection("Items", List.of("potions: awkward, swiftness"), null,
                List.of("potion:awkward", "potion:swiftness"));
        assertTrue(ids(Samples.snapshot(List.of(), List.of(items)), Map.of("potion", 2)).contains("drink_swiftness"));
        assertFalse(ids(Samples.snapshot(List.of(), List.of(items)), Map.of("potion", 2)).contains("drink_awkward"));
    }
}
