package com.aiciv.nether;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetherModuleTest {
    private final NetherModule module = new NetherModule();

    private static ActionFactory.Result create(String tool, String json) {
        return ActionFactory.create(ToolCall.of(tool, JsonParser.parseString(json).getAsJsonObject()));
    }

    private static String invalid(String tool, String json) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, create(tool, json), tool).reason();
    }

    private static SnapshotData snapshot(List<Sighting> blocks, String... tags) {
        return Samples.snapshot(blocks, List.of(new SnapshotSection("Nether", List.of(), null, List.of(tags))));
    }

    private List<String> ids(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory)).stream().map(MenuOption::id).toList();
    }

    @Test
    void everyToolIsAcceptedWithNoArguments() {
        for (String tool : List.of("build_portal", "cast_portal", "light_portal", "go_to_nether", "leave_nether", "find_fortress",
                "hunt_blazes", "get_blaze_rods")) {
            assertInstanceOf(ActionFactory.Result.Ok.class, create(tool, "{}"), tool);
        }
    }

    @Test
    void validationNamesWhatIsWrong() {
        assertTrue(invalid("build_portal", "{\"x\": 1}").contains("all of x, y and z"));
        assertTrue(invalid("cast_portal", "{\"x\": 1, \"y\": 70, \"z\": 2, \"facing\": \"up\"}").contains("facing must be N, E, S or W"));
        assertTrue(invalid("light_portal", "{\"y\": 70}").contains("light_portal"));
        assertTrue(invalid("find_fortress", "{\"direction\": \"NE\"}").contains("direction must be N, E, S or W"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("find_fortress", "{\"direction\": \"e\", \"distance\": 5000}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("build_portal", "{\"x\": 1, \"y\": 70, \"z\": 2, \"facing\": \"s\"}"));
    }

    @Test
    void limitsAreClamped() {
        ActionFactory.Result.Ok far = (ActionFactory.Result.Ok) create("find_fortress", "{\"distance\": 5000}");
        assertEquals(24000, far.action().timeoutTicks());
        ActionFactory.Result.Ok near = (ActionFactory.Result.Ok) create("find_fortress", "{\"distance\": 1}");
        assertEquals(40 * 64 + 600, near.action().timeoutTicks());
        ActionFactory.Result.Ok hunt = (ActionFactory.Result.Ok) create("hunt_blazes", "{\"count\": 99}");
        assertEquals(6000 + 1200 * 16, hunt.action().timeoutTicks());
        ActionFactory.Result.Ok one = (ActionFactory.Result.Ok) create("hunt_blazes", "{\"count\": 0}");
        assertEquals(6000 + 1200, one.action().timeoutTicks());
        ActionFactory.Result.Ok routine = (ActionFactory.Result.Ok) create("get_blaze_rods", "{}");
        assertEquals(36000, routine.action().timeoutTicks());
    }

    @Test
    void offersGoToNetherWithObsidianAndAnIgniterOrASeenPortal() {
        assertEquals(List.of("go_to_nether"), ids(Samples.snapshot(List.of(), List.of()), Map.of("obsidian", 14, "flint_and_steel", 1)));
        assertEquals(List.of(), ids(Samples.snapshot(List.of(), List.of()), Map.of("obsidian", 9, "flint_and_steel", 1)));
        assertEquals(List.of(), ids(Samples.snapshot(List.of(), List.of()), Map.of("obsidian", 14)));
        assertEquals(List.of("go_to_nether"), ids(Samples.snapshot(List.of(new Sighting("nether_portal", 6, 30, "N")), List.of()), Map.of()));
        assertEquals(List.of(), ids(Samples.snapshot(List.of(new Sighting("nether_portal", 6, 40, "N")), List.of()), Map.of()));
        assertEquals(List.of(), ids(snapshot(List.of(), "in_nether"), Map.of("obsidian", 14, "flint_and_steel", 1)).stream()
                .filter(id -> id.equals("go_to_nether")).toList());
    }

    @Test
    void offersNetherOptionsInTheNether() {
        assertEquals(List.of("find_fortress"), ids(snapshot(List.of(), "in_nether"), Map.of()));
        assertEquals(List.of("hunt_blazes"), ids(snapshot(List.of(), "in_nether", "fortress_known"), Map.of()));
        assertEquals(List.of("find_fortress", "hunt_blazes"), ids(snapshot(List.of(), "in_nether", "blaze_spawner_known"), Map.of()));
        assertEquals(List.of("hunt_blazes", "leave_nether"), ids(snapshot(List.of(), "in_nether", "fortress_known"), Map.of("blaze_rod", 6)));
        assertEquals(List.of("find_fortress"), ids(snapshot(List.of(), "in_nether"), Map.of("blaze_rod", 5)));
        MenuOption hunt = module.menuOptions(snapshot(List.of(), "in_nether", "fortress_known"), Samples.context(Map.of())).get(0);
        JsonObject args = hunt.calls().get(0).args();
        assertEquals(6, args.get("count").getAsInt());
    }

    @Test
    void sampleSituationOffersAValidOption() {
        List<MenuOption> options = module.menuOptions(module.sampleSnapshot(), module.sampleContext());
        assertEquals("go_to_nether", options.get(0).id());
    }
}
