package com.aiciv.modules;

import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure questions about a snapshot that modules ask to decide whether their tools are worth offering this turn
 * ({@link ToolModule#relevant}). They read only what the snapshot already says, so they cost nothing and are unit tested.
 */
public final class Situation {
    private Situation() {}

    /** Every section tag. */
    public static List<String> tags(SnapshotData snap) {
        return snap.sections().stream().map(SnapshotSection::tags).flatMap(List::stream).toList();
    }

    public static boolean tagged(SnapshotData snap, Predicate<String> tag) {
        return tags(snap).stream().anyMatch(tag);
    }

    /** The bot's dimension from the Dimension section's {@code dim:} tag: overworld, the_nether or the_end. */
    public static String dimension(SnapshotData snap) {
        return tags(snap).stream().filter(t -> t.startsWith("dim:")).map(t -> t.substring(4)).findFirst().orElse("overworld");
    }

    /** True when the bot carries, holds or wears an item whose id passes {@code id}. */
    public static boolean carries(SnapshotData snap, Predicate<String> id) {
        for (String entry : snap.inventory()) {
            int x = entry.lastIndexOf(" x");
            if (id.test(x > 0 ? entry.substring(0, x) : entry)) return true;
        }
        if (id.test(snap.holding())) return true;
        for (String worn : snap.equipment().split(",")) if (id.test(worn.trim())) return true;
        return false;
    }

    public static boolean carriesAny(SnapshotData snap, Set<String> ids) {
        return carries(snap, ids::contains);
    }

    /** A block whose label passes {@code label} is in sight within {@code distance} blocks. */
    public static boolean sees(SnapshotData snap, Predicate<String> label, int distance) {
        for (Sighting s : snap.blocks()) if (s.distance() <= distance && label.test(s.label())) return true;
        return false;
    }

    /** A mob whose type passes {@code type} is in view within {@code distance} blocks. */
    public static boolean seesMob(SnapshotData snap, Predicate<String> type, int distance) {
        for (Sighting s : snap.mobs()) if (s.distance() <= distance && type.test(s.label())) return true;
        return false;
    }
}
