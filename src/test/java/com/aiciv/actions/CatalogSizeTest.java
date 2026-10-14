package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogSizeTest {
    /** A bot early in an overworld game: some logs, stone and food, a pickaxe, a cow and a crafting table nearby. */
    static SnapshotData overworld() {
        return in("overworld", List.of("oak_log x6", "cobblestone x20", "bread x3", "stone_pickaxe", "crafting_table"), List.of());
    }

    static SnapshotData in(String dimension, List<String> inventory, List<String> equipment) {
        return new SnapshotData("Ada", 10, 64, -20, 20, 20, 18, 2, "morning", "plains", "stone_pickaxe",
                equipment.isEmpty() ? "none" : String.join(", ", equipment), inventory,
                List.of(new Sighting("oak_log", 12, 5, "N"), new Sighting("crafting_table", 1, 3, "E"), new Sighting("iron_ore", 2, 14, "W")),
                List.of(), List.of(new Sighting("cow", 2, 9, "S"), new Sighting("zombie", 1, 20, "E")), List.of(), "none", "nothing yet", 3,
                List.of(new SnapshotSection("Dimension", List.of(), null, List.of("dim:" + dimension))));
    }

    static Set<String> names(JsonArray tools) {
        Set<String> out = new HashSet<>();
        for (JsonElement t : tools) out.add(t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        return out;
    }

    @Test
    void fullCatalogStaysUnderTheBudget() {
        JsonArray tools = ToolCatalog.tools();
        int chars = tools.toString().length();
        int guide = ToolCatalog.guide().length();
        System.out.println("[CatalogSizeTest] " + tools.size() + " tools, " + chars + " chars (~" + chars / 4 + " tokens), guide " + guide + " chars");
        assertTrue(chars < 60_000, "the tool catalog is " + chars + " characters");
    }

    @Test
    void gatedOverworldCatalogIsSmall() {
        JsonArray tools = ToolCatalog.tools(ToolCatalog.Mode.GATED, overworld(), null);
        JsonArray compact = ToolCatalog.tools(ToolCatalog.Mode.COMPACT, overworld(), null);
        int chars = tools.toString().length();
        int guide = ToolCatalog.guide(ToolCatalog.Mode.GATED, overworld(), null).length();
        System.out.println("[CatalogSizeTest] gated overworld: " + tools.size() + " tools, " + chars + " chars (~" + chars / 4
                + " tokens), guide " + guide + " chars; compact " + compact.toString().length() + " chars");
        assertTrue(chars < 20_000, "the gated overworld catalog is " + chars + " characters");
        assertTrue(compact.toString().length() < chars);
    }

    @Test
    void dragonToolsOnlyInTheEnd() {
        Set<String> overworld = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, overworld(), null));
        Set<String> end = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("the_end", List.of("bow", "arrow x32"), List.of()), null));
        for (String dragon : List.of("fight_dragon", "shoot_crystals", "break_cage", "hit_dragon", "leave_end")) {
            assertFalse(overworld.contains(dragon), dragon + " offered in the overworld");
            assertTrue(end.contains(dragon), dragon + " missing in the End");
        }
        assertFalse(ToolCatalog.guide(ToolCatalog.Mode.GATED, overworld(), null).contains("fight_dragon"));
        assertTrue(ToolCatalog.guide(ToolCatalog.Mode.GATED, in("the_end", List.of(), List.of()), null).contains("fight_dragon"));
    }

    @Test
    void coreToolsAreAlwaysSent() {
        Set<String> bare = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("overworld", List.of(), List.of()), null));
        for (String core : List.of("say", "goTo", "mine", "craft", "smelt", "attack", "stop", "build", "deposit", "gear_up")) {
            assertTrue(bare.contains(core), core + " missing");
        }
        assertFalse(bare.contains("go_to_nether"));
        assertFalse(bare.contains("fly_to"));
        assertFalse(bare.contains("ride_to"));
    }

    @Test
    void situationsBringTheirModules() {
        Set<String> nether = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("the_nether", List.of(), List.of()), null));
        assertTrue(nether.contains("find_fortress") && nether.contains("enter_portal"), nether.toString());
        Set<String> obsidian = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("overworld", List.of("obsidian x10"), List.of()), null));
        assertTrue(obsidian.contains("build_portal"));
        Set<String> elytra = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("overworld", List.of(), List.of("elytra")), null));
        assertTrue(elytra.contains("fly_to"));
        Set<String> eyes = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("overworld", List.of("ender_eye x12"), List.of()), null));
        assertTrue(eyes.contains("locate_stronghold"));
        Set<String> boat = names(ToolCatalog.tools(ToolCatalog.Mode.GATED, in("overworld", List.of("oak_boat"), List.of()), null));
        assertTrue(boat.contains("boat_to"));
    }

    @Test
    void fullModeSendsEverythingAndCompactDropsParameterDescriptions() {
        assertEquals(ToolCatalog.tools(), ToolCatalog.tools(ToolCatalog.Mode.FULL, overworld(), null));
        assertEquals(ToolCatalog.guide(), ToolCatalog.guide(ToolCatalog.Mode.FULL, overworld(), null));
        JsonArray gated = ToolCatalog.tools(ToolCatalog.Mode.GATED, overworld(), null);
        JsonArray compact = ToolCatalog.tools(ToolCatalog.Mode.COMPACT, overworld(), null);
        assertEquals(names(gated), names(compact));
        for (JsonElement t : compact) {
            JsonObject fn = t.getAsJsonObject().getAsJsonObject("function");
            assertTrue(fn.has("description"));
            if (!fn.has("parameters")) continue;
            for (var p : fn.getAsJsonObject("parameters").getAsJsonObject("properties").entrySet()) {
                assertFalse(p.getValue().getAsJsonObject().has("description"), fn.get("name") + "." + p.getKey());
                assertTrue(p.getValue().getAsJsonObject().has("type"), fn.get("name") + "." + p.getKey());
            }
        }
    }
}
