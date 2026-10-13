package com.aiciv.gear;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GearModuleTest {
    private final GearModule module = new GearModule();

    private static ActionFactory.Result create(String json) {
        return ActionFactory.create(ToolCall.of("gear_up", JsonParser.parseString(json).getAsJsonObject()));
    }

    private static SnapshotData wearing(String equipment) {
        SnapshotData s = Samples.snapshot(List.of(), List.of());
        return new SnapshotData(s.name(), 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing", equipment, List.of(), List.of(),
                List.of(), List.of(), List.of(), "none", "nothing yet");
    }

    private List<MenuOption> menu(String equipment, Map<String, Integer> inventory, List<String> craftable) {
        return module.menuOptions(wearing(equipment),
                new OptionMenu.MenuContext(inventory, craftable, false, List.of(), List.of(), List.of()));
    }

    @Test
    void gearUpIsAcceptedWithNoArgumentsAndWithOptions() {
        assertInstanceOf(ActionFactory.Result.Ok.class, create("{}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("{\"material\": \"Iron\", \"shield\": false}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("{\"material\": \"gold\", \"shield\": \"false\"}"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("{\"material\": \"auto\"}"));
    }

    @Test
    void badMaterialsAreRefusedWithTheName() {
        String netherite = ((ActionFactory.Result.Invalid) create("{\"material\": \"netherite\"}")).reason();
        assertTrue(netherite.contains("smithing"), netherite);
        String wood = ((ActionFactory.Result.Invalid) create("{\"material\": \"wood\"}")).reason();
        assertTrue(wood.contains("\"wood\""), wood);
    }

    @Test
    void offersGearUpWhenACarriedPieceBeatsWhatIsWorn() {
        List<MenuOption> options = menu("leather_helmet", Map.of("iron_helmet", 1), List.of());
        assertEquals(List.of("gear_up"), options.stream().map(MenuOption::id).toList());
        assertEquals("gear_up", options.get(0).calls().get(0).name());
    }

    @Test
    void offersGearUpWhenSomethingCanBeMadeOrAShieldIsMissing() {
        assertEquals(1, menu("none", Map.of(), List.of("iron_chestplate")).size());
        assertEquals(1, menu("iron_helmet, iron_chestplate, iron_leggings, iron_boots", Map.of(), List.of("shield")).size());
        assertEquals(1, menu("iron_helmet, iron_chestplate, iron_leggings, iron_boots", Map.of("shield", 1), List.of()).size());
    }

    @Test
    void offersNothingWhenFullyGeared() {
        assertTrue(menu("iron_helmet, iron_chestplate, iron_leggings, iron_boots, shield", Map.of("leather_boots", 1),
                List.of("leather_helmet", "iron_boots")).isEmpty());
        assertTrue(menu("none", Map.of(), List.of()).isEmpty());
    }

    @Test
    void sampleSituationOffersAValidOption() {
        List<MenuOption> options = module.menuOptions(module.sampleSnapshot(), module.sampleContext());
        assertEquals(Set.of("gear_up"), Set.of(options.get(0).id()));
    }

    @Test
    void reportShowsArmorDurabilityAndWarnings() {
        List<String> lines = GearReport.lines(11,
                List.of(new GearReport.Entry("iron_helmet", 100), new GearReport.Entry("iron_chestplate", 34), new GearReport.Entry("shield", -1)),
                new GearReport.Entry("iron_pickaxe", 9));
        assertEquals(List.of("armor 11", "iron_pickaxe almost broken (9%)", "iron_helmet 100%", "iron_chestplate 34%", "shield",
                "holding iron_pickaxe 9%"), lines);
    }

    @Test
    void reportWarnsForWornItemsToo() {
        List<String> lines = GearReport.lines(2, List.of(new GearReport.Entry("iron_boots", 14)), null);
        assertEquals(List.of("armor 2", "iron_boots almost broken (14%)", "iron_boots 14%"), lines);
        assertEquals(List.of("armor 0"), GearReport.lines(0, List.of(), null));
        assertEquals(15, GearReport.WARN_BELOW_PERCENT);
        assertEquals(List.of("armor 1", "iron_boots 15%"), GearReport.lines(1, List.of(new GearReport.Entry("iron_boots", 15)), null));
    }

    @Test
    void percentLeftRoundsAndHandlesUndamageable() {
        assertEquals(34, GearReport.percentLeft(158, 240));
        assertEquals(100, GearReport.percentLeft(0, 250));
        assertEquals(-1, GearReport.percentLeft(0, 0));
    }

    @Test
    void toolDefinitionHasTheOptionalArguments() {
        JsonObject props = module.tools().get(0).getAsJsonObject("function").getAsJsonObject("parameters").getAsJsonObject("properties");
        assertTrue(props.has("material") && props.has("shield"));
    }
}
