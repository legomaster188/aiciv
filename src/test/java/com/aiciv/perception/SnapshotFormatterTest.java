package com.aiciv.perception;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotFormatterTest {
    static SnapshotData sample(List<Sighting> blocks) {
        return new SnapshotData("Ada", 102, 64, -33, 18, 20, 14, 3, "afternoon", "plains", "stone_pickaxe",
                "iron_chestplate, shield", List.of("oak_log x12", "bread x3"), blocks,
                List.of(new Sighting("Bob [AI]", 1, 6, "N")),
                List.of(new Sighting("zombie", 2, 15, "E")),
                List.of("iron_ore at 120 34 -50 (60m NE)"),
                "none", "mine iron_ore x10 failed: no path");
    }

    @Test
    void rendersEveryLine() {
        String text = SnapshotFormatter.format(sample(List.of(new Sighting("iron_ore", 3, 8, "NE"), new Sighting("crafting_table", 1, 3, "S"))), 6000);
        assertEquals("""
                You: Ada | pos 102 64 -33 | hp 18/20 food 14/20 | day 3, afternoon | biome plains
                Holding: stone_pickaxe
                Wearing: iron_chestplate, shield
                Inventory: oak_log x12, bread x3
                Blocks nearby: iron_ore x3 (nearest 8m NE), crafting_table (3m S)
                Players nearby: Bob [AI] (6m N)
                Mobs nearby: zombie x2 (nearest 15m E)
                Remembered: iron_ore at 120 34 -50 (60m NE)
                Current action: none (last: mine iron_ore x10 failed: no path)""", text);
    }

    @Test
    void emptyListsSayNothing() {
        SnapshotData s = new SnapshotData("Ada", 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing", "none",
                List.of(), List.of(), List.of(), List.of(), List.of(), "none", "nothing yet");
        String text = SnapshotFormatter.format(s, 6000);
        assertTrue(text.contains("Wearing: none"));
        assertTrue(text.contains("Inventory: empty"));
        assertTrue(text.contains("Blocks nearby: nothing notable"));
        assertTrue(text.contains("Players nearby: nobody"));
        assertTrue(text.contains("Mobs nearby: none"));
        assertTrue(text.contains("Remembered: nothing"));
    }

    @Test
    void longListsAreTruncatedWithACount() {
        List<Sighting> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(new Sighting("block_number_" + i, 1, i, "N"));
        String text = SnapshotFormatter.format(sample(many), 6000);
        assertTrue(text.length() <= 6000, "length " + text.length());
        assertTrue(text.contains("block_number_0 "), text);
        assertTrue(text.matches("(?s).*\\+\\d+ more.*"), text);
        assertTrue(text.contains("Current action:"));
    }

    @Test
    void rememberedIsTruncatedLikeTheOtherLists() {
        List<String> remembered = new ArrayList<>();
        for (int i = 0; i < 400; i++) remembered.add("iron_ore at " + i + " 30 0 (" + (i + 30) + "m E)");
        SnapshotData s = new SnapshotData("Ada", 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing", "none",
                List.of(), List.of(), List.of(), List.of(), remembered, "none", "nothing yet");
        String text = SnapshotFormatter.format(s, 2000);
        assertTrue(text.length() <= 2000, "length " + text.length());
        assertTrue(text.contains("Remembered: iron_ore at 0 30 0"), text);
        assertTrue(text.matches("(?s).*Remembered: [^\\n]*\\+\\d+ more.*"), text);
    }
}
