package com.aiciv.animals;

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

class AnimalsModuleTest {
    private final AnimalsModule module = new AnimalsModule();

    private static ActionFactory.Result create(String name, String key, String value) {
        JsonObject args = new JsonObject();
        if (key != null) args.addProperty(key, value);
        return ActionFactory.create(ToolCall.of(name, args));
    }

    private static SnapshotSection pets(String... tags) {
        return new SnapshotSection("Pets", List.of(), null, List.of(tags));
    }

    private List<MenuOption> options(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory));
    }

    private static MenuOption find(List<MenuOption> options, String id) {
        return options.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void unknownColorIsInvalidAndListsTheColors() {
        ActionFactory.Result r = create("dye_sheep", "color", "teal");
        String reason = assertInstanceOf(ActionFactory.Result.Invalid.class, r).reason();
        assertTrue(reason.contains("unknown color"), reason);
        assertTrue(reason.contains("light_blue") && reason.contains("black"), reason);
    }

    @Test
    void knownColorsAreValid() {
        assertInstanceOf(ActionFactory.Result.Ok.class, create("dye_sheep", "color", "red"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("dyesheep", "color", "Light Blue"));
    }

    @Test
    void requiredArgumentsAreNamed() {
        for (String tool : List.of("tame", "saddle", "leash", "unleash")) {
            String reason = assertInstanceOf(ActionFactory.Result.Invalid.class, create(tool, null, null), tool).reason();
            assertTrue(reason.contains("\"animal\""), reason);
        }
        assertTrue(assertInstanceOf(ActionFactory.Result.Invalid.class, create("dye_sheep", null, null)).reason().contains("\"color\""));
    }

    @Test
    void optionalArgumentsMayBeLeftOut() {
        for (String tool : List.of("milk", "shear", "tie_lead")) assertInstanceOf(ActionFactory.Result.Ok.class, create(tool, null, null), tool);
    }

    @Test
    void partialFenceCoordinatesAreInvalid() {
        assertInstanceOf(ActionFactory.Result.Invalid.class, create("tie_lead", "x", "1"));
    }

    @Test
    void milkAndShearRefuseOtherAnimals() {
        assertInstanceOf(ActionFactory.Result.Invalid.class, create("milk", "animal", "pig"));
        assertInstanceOf(ActionFactory.Result.Invalid.class, create("shear", "animal", "cow"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("milk", "animal", "goats"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("shear", "animal", "snow_golem"));
    }

    @Test
    void tameOffersAtMostTwoTameables() {
        SnapshotData snap = Samples.snapshot(List.of(), List.of(), List.of(pets("tameable:wolf", "tameable:cat", "tameable:parrot")));
        List<MenuOption> options = options(snap, Map.of());
        assertNotNull(find(options, "tame_wolf"));
        assertNotNull(find(options, "tame_cat"));
        assertNull(find(options, "tame_parrot"));
        ToolCall call = find(options, "tame_wolf").calls().get(0);
        assertEquals("tame", call.name());
        assertEquals("wolf", call.args().get("animal").getAsString());
    }

    @Test
    void milkAndShearNeedTheToolAndAnAnimalInReach() {
        SnapshotData near = Samples.snapshot(List.of(), List.of(new Sighting("cow", 1, 8, "N"), new Sighting("sheep", 1, 3, "E")), List.of());
        SnapshotData far = Samples.snapshot(List.of(), List.of(new Sighting("cow", 1, 9, "N"), new Sighting("sheep", 1, 9, "E")), List.of());
        List<MenuOption> options = options(near, Map.of("bucket", 1, "shears", 1));
        assertNotNull(find(options, "milk_cow"));
        assertNotNull(find(options, "shear_sheep"));
        assertNull(find(options(near, Map.of()), "milk_cow"));
        assertNull(find(options(near, Map.of()), "shear_sheep"));
        assertNull(find(options(far, Map.of("bucket", 1, "shears", 1)), "milk_cow"));
        assertNull(find(options(far, Map.of("bucket", 1, "shears", 1)), "shear_sheep"));
    }

    @Test
    void tieLeadWhenLeading() {
        SnapshotData snap = Samples.snapshot(List.of(), List.of(), List.of(pets("leading")));
        MenuOption tie = find(options(snap, Map.of()), "tie_lead");
        assertNotNull(tie);
        assertEquals("tie_lead", tie.calls().get(0).name());
        assertNull(find(options(Samples.snapshot(List.of(), List.of()), Map.of()), "tie_lead"));
    }
}
