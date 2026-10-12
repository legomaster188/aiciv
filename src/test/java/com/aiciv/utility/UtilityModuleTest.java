package com.aiciv.utility;

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

class UtilityModuleTest {
    private final UtilityModule module = new UtilityModule();

    private ActionFactory.Result create(String name, String json) {
        return ActionFactory.create(ToolCall.of(name, JsonParser.parseString(json).getAsJsonObject()));
    }

    @Test
    void useBlockWithoutZNamesIt() {
        ActionFactory.Result r = create("use_block", "{\"x\": 1, \"y\": 64}");
        assertInstanceOf(ActionFactory.Result.Invalid.class, r);
        assertTrue(((ActionFactory.Result.Invalid) r).reason().contains("\"z\""), r.toString());
        assertInstanceOf(ActionFactory.Result.Ok.class, create("useBlock", "{\"x\": 1, \"y\": 64, \"z\": 3}"));
    }

    @Test
    void enchantOptionClampsToThree() {
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("enchant", "{\"item\": \"iron_pickaxe\", \"option\": 7}"));
        assertEquals("enchant iron_pickaxe 3", ok.action().describe());
        ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("enchant", "{\"item\": \"bow\"}"));
        assertEquals("enchant bow 1", ok.action().describe());
    }

    @Test
    void brewCountClampsAndNamesNormalise() {
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("brew", "{\"potion\": \"speed\", \"count\": 9}"));
        assertEquals("brew swiftness x3", ok.action().describe());
    }

    private SnapshotData snap(boolean night, String... blocks) {
        List<Sighting> sightings = java.util.Arrays.stream(blocks).map(b -> new Sighting(b, 1, 4, "north")).toList();
        List<SnapshotSection> sections = night
                ? List.of(new SnapshotSection("Rest", List.of(UtilityModule.NIGHT_LINE), null, List.of(UtilityModule.CAN_SLEEP)))
                : List.of();
        return Samples.snapshot(sightings, sections);
    }

    private List<String> ids(SnapshotData snap, Map<String, Integer> inventory) {
        return module.menuOptions(snap, Samples.context(inventory)).stream().map(MenuOption::id).toList();
    }

    @Test
    void sleepOptionNeedsNightAndABed() {
        assertEquals(List.of("sleep"), ids(snap(true, "red_bed"), Map.of()));
        assertEquals(List.of("sleep"), ids(snap(true), Map.of("white_bed", 1)));
        assertEquals(List.of(), ids(snap(false, "red_bed"), Map.of("white_bed", 1)));
        assertEquals(List.of(), ids(snap(true), Map.of()));
    }

    @Test
    void enchantAndBrewOptionsNeedTheirStationAndSupplies() {
        assertEquals(List.of("enchant_diamond_pickaxe"),
                ids(snap(false, "enchanting_table"), Map.of("lapis_lazuli", 3, "diamond_pickaxe", 1, "iron_sword", 1)));
        assertEquals(List.of(), ids(snap(false, "enchanting_table"), Map.of("diamond_pickaxe", 1)));
        assertEquals(List.of("brew_awkward"),
                ids(snap(false, "brewing_stand"), Map.of("nether_wart", 1, "blaze_powder", 1, "glass_bottle", 3)));
        assertEquals(List.of(), ids(snap(false, "brewing_stand"), Map.of("nether_wart", 1, "glass_bottle", 3)));
    }

    @Test
    void sampleSnapshotOffersSleep() {
        assertFalse(module.menuOptions(module.sampleSnapshot(), module.sampleContext()).isEmpty());
        JsonObject none = new JsonObject();
        assertNotNull(module.create("sleep", none));
    }
}
