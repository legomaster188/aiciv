package com.aiciv.stations;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.perception.Sighting;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StationsModuleTest {
    private final StationsModule module = new StationsModule();

    private ActionFactory.Result create(String name, String json) {
        return ActionFactory.create(ToolCall.of(name, JsonParser.parseString(json).getAsJsonObject()));
    }

    private String invalid(String name, String json) {
        return assertInstanceOf(ActionFactory.Result.Invalid.class, create(name, json)).reason();
    }

    @Test
    void cartographyRejectsUnknownActions() {
        assertTrue(invalid("cartography", "{\"action\": \"fold\"}").contains("zoom, copy or lock"));
        assertTrue(invalid("cartography", "{}").contains("\"action\""));
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("cartography", "{\"action\": \"Zoom\"}"));
        assertEquals("cartography zoom", ok.action().describe());
    }

    @Test
    void writeBookChecksTitleAndLength() {
        assertTrue(invalid("write_book", "{\"text\": \"hi\", \"title\": \"" + "t".repeat(33) + "\"}").contains("title"));
        assertTrue(invalid("write_book", "{\"text\": \"" + "x".repeat(10241) + "\"}").contains("10240"));
        assertTrue(invalid("write_book", "{}").contains("\"text\""));
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class,
                create("writeBook", "{\"text\": \"Day 1\", \"title\": \"" + "t".repeat(32) + "\"}"));
        assertTrue(ok.action().describe().startsWith("write 1 page and sign it"));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("write_book", "{\"text\": \"" + "x".repeat(10240) + "\"}"));
    }

    @Test
    void upgradeNetheriteOnlyTakesDiamondGear() {
        assertTrue(invalid("upgrade_netherite", "{\"item\": \"iron_sword\"}").contains("diamond"));
        assertTrue(invalid("upgrade_netherite", "{}").contains("\"item\""));
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("upgradenetherite", "{\"item\": \"diamond_sword\"}"));
        assertEquals("upgrade diamond_sword to netherite", ok.action().describe());
    }

    @Test
    void trimArmorChecksTemplateAndMaterial() {
        assertTrue(invalid("trim_armor", "{\"item\": \"iron_chestplate\", \"template\": \"sparkle\", \"material\": \"iron\"}").contains("no trim called sparkle"));
        assertTrue(invalid("trim_armor", "{\"item\": \"iron_chestplate\", \"template\": \"coast\", \"material\": \"dirt\"}").contains("trim material"));
        assertTrue(invalid("trim_armor", "{\"item\": \"iron_chestplate\"}").contains("\"template\""));
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class,
                create("trim_armor", "{\"item\": \"iron_chestplate\", \"template\": \"coast\", \"material\": \"iron\"}"));
        assertEquals("trim iron_chestplate with coast_armor_trim_smithing_template and iron_ingot", ok.action().describe());
    }

    @Test
    void stonecutClampsCountAndLoomChecksColours() {
        ActionFactory.Result.Ok ok = assertInstanceOf(ActionFactory.Result.Ok.class, create("stonecut", "{\"input\": \"stone\", \"output\": \"stone_bricks\", \"count\": 500}"));
        assertEquals("stonecut stone into stone_bricks x64", ok.action().describe());
        assertTrue(invalid("loom", "{\"pattern\": \"cross\", \"color\": \"plaid\"}").contains("no dye colour"));
        assertTrue(invalid("loom", "{\"pattern\": \"cross\"}").contains("\"color\""));
        assertInstanceOf(ActionFactory.Result.Ok.class, create("loom", "{\"pattern\": \"bottom stripe\", \"color\": \"red\", \"banner\": \"white\"}"));
        assertTrue(invalid("loom", "{\"pattern\": \"cross\", \"color\": \"red\", \"banner\": \"plaid\"}").contains("banner colour"));
        assertTrue(invalid("grindstone", "{}").contains("\"item\""));
        assertTrue(invalid("repair", "{}").contains("\"item\""));
    }

    private List<String> ids(List<Sighting> sightings, Map<String, Integer> inventory) {
        return module.menuOptions(Samples.snapshot(sightings, List.of()), Samples.context(inventory)).stream().map(MenuOption::id).toList();
    }

    @Test
    void menuOffersTheUpgradeAndTheStonecutterOnlyWithTheirSupplies() {
        Map<String, Integer> kit = Map.of("diamond_sword", 1, "netherite_upgrade_smithing_template", 1, "netherite_ingot", 1);
        assertEquals(List.of("upgrade_diamond_sword"), ids(List.of(new Sighting("smithing_table", 2, 3, "N")), kit));
        assertEquals(List.of(), ids(List.of(new Sighting("smithing_table", 2, 30, "N")), kit));
        assertEquals(List.of(), ids(List.of(new Sighting("smithing_table", 2, 3, "N")), Map.of("diamond_sword", 1, "netherite_ingot", 1)));
        assertEquals(List.of(), ids(List.of(), kit));
        MenuOption cut = module.menuOptions(Samples.snapshot(List.of(new Sighting("stonecutter", 2, 3, "N")), List.of()), Samples.context(Map.of("stone", 40))).get(0);
        assertEquals("stonecut_stone_bricks", cut.id());
        assertEquals(16, cut.calls().get(0).args().get("count").getAsInt());
        assertEquals(List.of(), ids(List.of(new Sighting("stonecutter", 2, 3, "N")), Map.of("stone", 3)));
        assertFalse(module.menuOptions(module.sampleSnapshot(), module.sampleContext()).isEmpty());
    }
}
