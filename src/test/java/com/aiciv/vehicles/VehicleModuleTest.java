package com.aiciv.vehicles;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.testutil.FakeContext;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VehicleModuleTest {
    private final VehicleModule module = new VehicleModule();

    private static ActionFactory.Result create(String tool, String json) {
        return ActionFactory.create(ToolCall.of(tool, JsonParser.parseString(json).getAsJsonObject()));
    }

    private static String invalid(String tool, String json) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, create(tool, json)).reason();
    }

    @Test
    void rideToNeedsWholeNumbers() {
        assertEquals("ride_to needs a whole number \"x\"", invalid("ride_to", "{\"z\": 5}"));
        assertEquals("ride_to needs a whole number \"z\"", invalid("ride_to", "{\"x\": 5}"));
        assertEquals("boat_to needs a whole number \"x\"", invalid("boat_to", "{\"x\": \"far\", \"z\": 5}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("ride_to", "{\"x\": 5, \"z\": -9}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("rideto", "{\"x\": \"5\", \"z\": 9.7}"));
    }

    @Test
    void boardNamesTheKindsItKnows() {
        assertEquals("board \"target\" must be one of boat, minecart, horse, donkey, mule, llama, camel, pig, strider",
                invalid("board", "{\"target\": \"dragon\"}"));
        assertTrue(invalid("board", "{}").contains("\"target\""));
        assertEquals("board horse", assertInstanceOf(ActionFactory.Result.Ok.class, create("board", "{\"target\": \" Horse \"}")).action().describe());
    }

    @Test
    void placeMinecartWantsAllThreeCoordinatesOrNone() {
        assertInstanceOf(ActionFactory.Result.Ok.class, create("place_minecart", "{}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("place_minecart", "{\"x\": 1, \"y\": 64, \"z\": 2}"));
        assertTrue(invalid("place_minecart", "{\"x\": 1}").contains("all of x, y and z"));
    }

    @Test
    void ridingFartherThan512BlocksFailsWithoutMoving() {
        ActionStatus status = new RideToAction(600, 0).tick(new FakeContext());
        assertEquals(ActionStatus.State.FAILED, status.state());
        assertEquals("ride_to goes at most 512 blocks per call", status.reason());
        status = new BoatToRoutine(0, -513).tick(new FakeContext());
        assertEquals(ActionStatus.State.FAILED, status.state());
        assertTrue(status.reason().contains("boat_to") && status.reason().contains("at most 512 blocks per call"), status.reason());
    }

    @Test
    void rideTimeoutGrowsWithDistance() {
        assertEquals(600 + 20 * 100, RideToAction.timeoutFor(100));
        assertEquals(24000, RideToAction.timeoutFor(5000));
        assertEquals(24000, new RideToAction(10, 10).timeoutTicks()); // until it has seen where it starts
    }

    private static SnapshotData snapshot(String holding, List<Sighting> blocks, SnapshotSection... sections) {
        SnapshotData s = Samples.snapshot(blocks, List.of(sections));
        return new SnapshotData(s.name(), 100, s.y(), -40, s.health(), s.maxHealth(), s.food(), s.day(), s.timeOfDay(), s.biome(),
                holding, s.equipment(), s.inventory(), s.blocks(), s.players(), s.mobs(), s.remembered(), s.currentAction(),
                s.lastResult(), s.xpLevel(), s.sections());
    }

    private List<String> ids(SnapshotData snap) {
        return module.menuOptions(snap, Samples.context(Map.of())).stream().map(MenuOption::id).toList();
    }

    @Test
    void offersDismountWhileRiding() {
        SnapshotSection riding = new SnapshotSection("Riding", List.of("horse (tamed, saddled)"), null, List.of("riding", "riding:horse"));
        assertEquals(List.of("dismount"), ids(snapshot("nothing", List.of(), riding)));
        assertEquals(List.of(), ids(snapshot("nothing", List.of())));
    }

    @Test
    void offersToBoardAtMostTwoRideables() {
        SnapshotSection mounts = new SnapshotSection("Riding", List.of("a", "b", "c"), null, List.of("rideable:horse", "rideable:boat", "rideable:pig"));
        assertEquals(List.of("board_horse", "board_boat"), ids(snapshot("nothing", List.of(), mounts)));
        MenuOption board = module.menuOptions(snapshot("nothing", List.of(), mounts), Samples.context(Map.of())).get(0);
        assertEquals("horse", board.calls().get(0).args().get("target").getAsString());
    }

    @Test
    void offersBoatTripsOnlyWithABoatInHandAndWaterClose() {
        List<Sighting> water = List.of(new Sighting("water", 9, 2, "E"));
        List<MenuOption> options = module.menuOptions(snapshot("oak_boat", water), Samples.context(Map.of()));
        assertEquals(List.of("boat_to_n", "boat_to_e", "boat_to_s", "boat_to_w"), options.stream().map(MenuOption::id).toList());
        ToolCall east = options.get(1).calls().get(0);
        assertEquals("boat_to", east.name());
        assertEquals(164, east.args().get("x").getAsInt());
        assertEquals(-40, east.args().get("z").getAsInt());
        assertEquals(-104, options.get(0).calls().get(0).args().get("z").getAsInt());
        assertEquals(List.of(), ids(snapshot("stick", water)));
        assertEquals(List.of(), ids(snapshot("oak_boat", List.of(new Sighting("water", 9, 6, "E")))));
        assertEquals(List.of(), ids(snapshot("oak_boat", List.of())));
        assertEquals(4, ids(snapshot("bamboo_raft", water)).size());
    }
}
