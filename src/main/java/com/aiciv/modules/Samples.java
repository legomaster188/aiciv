package com.aiciv.modules;

import com.aiciv.brain.OptionMenu;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import java.util.List;
import java.util.Map;

/** Pure fixtures for module menus: a plain daytime snapshot and a menu context, varied only where a module needs it. */
public final class Samples {
    private Samples() {}

    public static SnapshotData snapshot(List<Sighting> blocks, List<SnapshotSection> sections) {
        return snapshot(blocks, List.of(), sections);
    }

    public static SnapshotData snapshot(List<Sighting> blocks, List<Sighting> mobs, List<SnapshotSection> sections) {
        return new SnapshotData("Sample", 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing", "none",
                List.of(), blocks, List.of(), mobs, List.of(), "none", "nothing yet", 0, sections);
    }

    public static OptionMenu.MenuContext context(Map<String, Integer> inventory) {
        return context(inventory, List.of());
    }

    public static OptionMenu.MenuContext context(Map<String, Integer> inventory, List<String> hostileMobs) {
        return new OptionMenu.MenuContext(inventory, List.of(), false, hostileMobs, List.of(), List.of());
    }
}
