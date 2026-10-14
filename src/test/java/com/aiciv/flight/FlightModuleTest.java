package com.aiciv.flight;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlightModuleTest {
    private final FlightModule module = new FlightModule();

    private static ActionFactory.Result create(String tool, String json) {
        return ActionFactory.create(ToolCall.of(tool, JsonParser.parseString(json).getAsJsonObject()));
    }

    private static SnapshotData with(int x, int z, String... tags) {
        SnapshotData base = Samples.snapshot(List.of(), List.of(new SnapshotSection("Flight", List.of("elytra 87% (worn)"), null, List.of(tags))));
        return new SnapshotData(base.name(), x, base.y(), z, base.health(), base.maxHealth(), base.food(), base.day(), base.timeOfDay(),
                base.biome(), base.holding(), base.equipment(), base.inventory(), base.blocks(), base.players(), base.mobs(),
                base.remembered(), base.currentAction(), base.lastResult(), 0, base.sections());
    }

    @Test
    void throwPearlWithoutYIsInvalidAndNamesIt() {
        ActionFactory.Result.Invalid invalid = assertInstanceOf(ActionFactory.Result.Invalid.class, create("throw_pearl", "{\"x\": 4, \"z\": 4}"));
        assertTrue(invalid.reason().contains("\"y\""), invalid.reason());
    }

    @Test
    void flyToNeedsXAndZ() {
        assertTrue(assertInstanceOf(ActionFactory.Result.Invalid.class, create("fly_to", "{\"z\": 4}")).reason().contains("\"x\""));
        assertTrue(assertInstanceOf(ActionFactory.Result.Invalid.class, create("fly_to", "{\"x\": 4}")).reason().contains("\"z\""));
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("fly_to", "{\"x\": 100, \"z\": -50}"));
        assertEquals("flyTo 100 -50", ok.action().describe());
    }

    @Test
    void theOtherToolsTakeNoArguments() {
        assertInstanceOf(ActionFactory.Result.Ok.class, create("launch_elytra", "{}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("boost", "{}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("throw_pearl", "{\"x\": 4, \"y\": 64, \"z\": 4}"));
    }

    private List<String> ids(SnapshotData snap) {
        return module.menuOptions(snap, Samples.context(Map.of())).stream().map(MenuOption::id).toList();
    }

    @Test
    void menuOffersLaunchAndFourFlightsWhenWearingAnElytraWithRockets() {
        assertEquals(List.of("launch_elytra", "fly_north", "fly_east", "fly_south", "fly_west"),
                ids(with(10, 20, "has_elytra", "elytra_worn", "rockets")));
        MenuOption north = module.menuOptions(with(10, 20, "has_elytra", "elytra_worn", "rockets"), Samples.context(Map.of())).get(1);
        JsonObject args = north.calls().get(0).args();
        assertEquals("fly_to", north.calls().get(0).name());
        assertEquals(10, args.get("x").getAsInt());
        assertEquals(20 - 256, args.get("z").getAsInt());
        MenuOption east = module.menuOptions(with(10, 20, "has_elytra", "elytra_worn", "rockets"), Samples.context(Map.of())).get(2);
        assertEquals(10 + 256, east.calls().get(0).args().get("x").getAsInt());
        assertEquals(20, east.calls().get(0).args().get("z").getAsInt());
    }

    @Test
    void menuOffersNothingWithoutAWornElytraAndRockets() {
        assertEquals(List.of(), ids(with(0, 0, "has_elytra", "rockets")));
        assertEquals(List.of(), ids(with(0, 0, "has_elytra", "elytra_worn")));
        assertEquals(List.of(), ids(with(0, 0, "pearls")));
        assertEquals(List.of(), ids(Samples.snapshot(List.of(), List.of())));
    }

    @Test
    void theSampleOffersAtLeastOneOption() {
        assertFalse(module.menuOptions(module.sampleSnapshot(), module.sampleContext()).isEmpty());
    }
}
