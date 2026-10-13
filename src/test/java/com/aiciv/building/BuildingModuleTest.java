package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingModuleTest {
    private final BuildingModule module = new BuildingModule();

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String valid(String extra) {
        return "{\"name\":\"my_hut\",\"layers\":[[\"ccc\",\"c.c\",\"ccc\"]],\"legend\":{\"c\":\"cobblestone\"}" + extra + "}";
    }

    private static ActionFactory.Result build(String json) {
        return ActionFactory.create(ToolCall.of("build", args(json)));
    }

    private static String invalid(ActionFactory.Result r) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, r, r.toString()).reason();
    }

    @Test
    void aValidBuildIsOk() {
        assertInstanceOf(ActionFactory.Result.Ok.class, build(valid("")));
        assertInstanceOf(ActionFactory.Result.Ok.class, build(valid(",\"x\":1,\"y\":64,\"z\":-3,\"facing\":\"N\"")));
    }

    @Test
    void missingArgumentsNameThemselves() {
        assertEquals("build needs a \"name\"", invalid(build("{}")));
        assertEquals("build needs \"layers\"", invalid(build("{\"name\":\"a\"}")));
        assertEquals("build needs a \"legend\"", invalid(build("{\"name\":\"a\",\"layers\":[[\"c\"]]}")));
    }

    @Test
    void badFacingNameAndPartialCoordinatesAreInvalid() {
        assertEquals("facing must be N, E, S or W", invalid(build(valid(",\"facing\":\"up\""))));
        assertTrue(invalid(build(valid("").replace("my_hut", "Bad/Name!"))).contains("\"name\" may use only a-z, 0-9 and _"));
        assertTrue(invalid(build(valid(",\"x\":1,\"z\":2"))).contains("needs all of x, y and z, or none"));
    }

    @Test
    void blueprintProblemsComeBackAsTheReason() {
        String r = invalid(build("{\"name\":\"a\",\"layers\":[[\"cx\"]],\"legend\":{\"c\":\"stone\"}}"));
        assertEquals("unknown symbol 'x' in layer 1 row 1 (add it to the legend)", r);
        String door = invalid(build("{\"name\":\"a\",\"layers\":[[\"d\"],[\".\"]],\"legend\":{\"d\":\"oak_door\"}}"));
        assertTrue(door.startsWith("door at layer 1 row 1 col 1"), door);
    }

    @Test
    void namesAreNormalised() {
        assertInstanceOf(ActionFactory.Result.Ok.class, build(valid("").replace("my_hut", "My Hut")));
    }

    @Test
    void buildTemplateValidates() {
        ActionFactory.Result ok = ActionFactory.create(ToolCall.of("build_template",
                args("{\"template\":\"hut\",\"material\":\"cobblestone\",\"name\":\"h1\",\"size\":5}")));
        assertInstanceOf(ActionFactory.Result.Ok.class, ok);
        assertEquals("build_template needs a \"template\"", invalid(ActionFactory.create(ToolCall.of("build_template", args("{}")))));
        assertEquals("template must be one of hut, tower, wall, bridge, farm_plot", invalid(ActionFactory.create(ToolCall.of("buildTemplate",
                args("{\"template\":\"castle\",\"material\":\"stone\",\"name\":\"h1\"}")))));
    }

    @Test
    void menuOffersAHutAt71PlanksAndNotAt70() {
        SnapshotData snap = Samples.snapshot(List.of(), List.of());
        List<MenuOption> at71 = module.menuOptions(snap, Samples.context(Map.of("oak_planks", 71)));
        assertTrue(at71.stream().anyMatch(o -> o.id().equals("build_hut")));
        assertTrue(at71.stream().anyMatch(o -> o.id().equals("build_wall")));
        List<MenuOption> at70 = module.menuOptions(snap, Samples.context(Map.of("oak_planks", 70)));
        assertTrue(at70.stream().noneMatch(o -> o.id().equals("build_hut")));
        assertTrue(at70.stream().anyMatch(o -> o.id().equals("build_wall")));
        assertTrue(module.menuOptions(snap, Samples.context(Map.of("oak_planks", 23))).isEmpty());
    }

    @Test
    void menuNamesUseTheSnapshotPositionWithMForNegatives() {
        SnapshotData snap = new SnapshotData("Sample", -12, 64, 5, 20, 20, 20, 1, "morning", "plains", "nothing", "none",
                List.of(), List.of(), List.of(), List.of(), List.of(), "none", "nothing yet", 0, List.of());
        MenuOption hut = module.menuOptions(snap, Samples.context(Map.of("cobblestone", 80))).stream()
                .filter(o -> o.id().equals("build_hut")).findFirst().orElseThrow();
        ToolCall call = hut.calls().get(0);
        assertEquals("build_template", call.name());
        assertEquals("hut_m12_5", call.args().get("name").getAsString());
        assertEquals("cobblestone", call.args().get("material").getAsString());
        assertEquals(5, call.args().get("size").getAsInt());
    }

    @Test
    void theMostPlentifulMaterialWins() {
        List<MenuOption> options = module.menuOptions(Samples.snapshot(List.of(), List.of()),
                Samples.context(Map.of("oak_planks", 10, "stone_bricks", 80, "dirt", 500)));
        MenuOption hut = options.stream().filter(o -> o.id().equals("build_hut")).findFirst().orElseThrow();
        assertEquals("stone_bricks", hut.calls().get(0).args().get("material").getAsString());
    }

    @Test
    void sampleContextHoldsEnoughPlanks() {
        assertEquals(80, module.sampleContext().inventory().get("oak_planks"));
    }
}
