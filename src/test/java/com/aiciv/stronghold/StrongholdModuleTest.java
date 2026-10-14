package com.aiciv.stronghold;

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

class StrongholdModuleTest {
    private final StrongholdModule module = new StrongholdModule();

    private static ActionFactory.Result create(String tool, String json) {
        return ActionFactory.create(ToolCall.of(tool, JsonParser.parseString(json).getAsJsonObject()));
    }

    private static String describe(ActionFactory.Result r) {
        return assertInstanceOf(ActionFactory.Result.Ok.class, r).action().describe();
    }

    @Test
    void makeEyesClampsItsCount() {
        assertEquals("make 12 ender_eye", describe(create("make_eyes", "{}")));
        assertEquals("make 1 ender_eye", describe(create("make_eyes", "{\"count\": 0}")));
        assertEquals("make 12 ender_eye", describe(create("make_eyes", "{\"count\": 40}")));
        assertEquals("make 5 ender_eye", describe(create("make_eyes", "{\"count\": \"5\"}")));
    }

    @Test
    void everyToolIsReachableByItsName() {
        for (String tool : List.of("throw_eye", "locate_stronghold", "go_to_stronghold", "find_portal_room", "fill_end_portal", "go_to_end")) {
            assertInstanceOf(ActionFactory.Result.Ok.class, create(tool, "{}"), tool);
        }
        assertEquals(7, module.tools().size());
        assertNull(module.create("nothing", new JsonObject()));
    }

    @Test
    void locateDescribesItself() {
        assertEquals("locate the stronghold", describe(create("locate_stronghold", "{\"spread\": 5}")));
        assertEquals("go to the stronghold", describe(create("go_to_stronghold", "{}")));
        assertEquals("go to the End", describe(create("go_to_end", "{}")));
    }

    private List<String> ids(Map<String, Integer> inv, List<Sighting> blocks, String... tags) {
        List<SnapshotSection> sections = tags.length == 0 ? List.of() : List.of(new SnapshotSection("Stronghold", List.of(), null, List.of(tags)));
        SnapshotData snap = Samples.snapshot(blocks, sections);
        return module.menuOptions(snap, Samples.context(inv)).stream().map(MenuOption::id).toList();
    }

    @Test
    void locateNeedsTwoEyesAndNoEstimate() {
        assertEquals(List.of("locate_stronghold"), ids(Map.of("ender_eye", 2), List.of()));
        assertEquals(List.of(), ids(Map.of("ender_eye", 1), List.of()));
        assertEquals(List.of(), ids(Map.of("ender_eye", 12), List.of(), "dim:the_nether"));
        assertEquals(List.of("go_to_stronghold"), ids(Map.of("ender_eye", 12), List.of(), "stronghold_known"));
    }

    @Test
    void goToStrongholdUntilThePortalRoomIsKnown() {
        assertEquals(List.of("go_to_stronghold"), ids(Map.of(), List.of(), "stronghold_known"));
        assertEquals(List.of("go_to_end"), ids(Map.of(), List.of(), "stronghold_known", "portal_room_known"));
        assertEquals(List.of("go_to_end"), ids(Map.of(), List.of(new Sighting("end_portal_frame", 12, 20, "N"))));
        assertEquals(List.of(), ids(Map.of(), List.of(new Sighting("end_portal_frame", 12, 40, "N"))));
    }

    @Test
    void makeEyesNeedsPearlsAndPowderOrRods() {
        assertEquals(List.of("make_eyes"), ids(Map.of("ender_pearl", 3, "blaze_rod", 2), List.of()));
        assertEquals(List.of("make_eyes"), ids(Map.of("ender_pearl", 20, "blaze_powder", 1), List.of(), "dim:the_nether"));
        assertEquals(List.of(), ids(Map.of("ender_pearl", 3), List.of()));
        assertEquals(List.of(), ids(Map.of("blaze_rod", 3), List.of()));
        assertEquals(List.of(), ids(Map.of("ender_pearl", 3, "blaze_rod", 2), List.of(), "dim:the_end"));
        MenuOption o = module.menuOptions(Samples.snapshot(List.of(), List.of()), Samples.context(Map.of("ender_pearl", 20, "blaze_rod", 9))).get(0);
        assertEquals(12, o.calls().get(0).args().get("count").getAsInt());
    }

    @Test
    void theSampleOffersALocateOption() {
        List<MenuOption> options = module.menuOptions(module.sampleSnapshot(), module.sampleContext());
        assertEquals(List.of("locate_stronghold"), options.stream().map(MenuOption::id).toList());
    }
}
