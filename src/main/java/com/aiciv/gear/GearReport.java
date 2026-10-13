package com.aiciv.gear;

import java.util.ArrayList;
import java.util.List;

/** The snapshot's {@code Gear:} line: armor points, durability of what is worn and held, and warnings for nearly broken items. */
public final class GearReport {
    /** Below this many percent of durability left an item is called almost broken. */
    public static final int WARN_BELOW_PERCENT = 15;

    /** {@code percent} is the durability left, or -1 for an item that cannot wear out. */
    public record Entry(String id, int percent) {}

    private GearReport() {}

    public static int percentLeft(int damage, int maxDamage) {
        if (maxDamage <= 0) return -1;
        return (int) Math.round(100.0 * (maxDamage - damage) / maxDamage);
    }

    /** Warnings come first: the formatter drops items from the end when the snapshot is too long. */
    public static List<String> lines(int armorPoints, List<Entry> worn, Entry held) {
        List<String> warnings = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (Entry e : worn) {
            items.add(e.percent() < 0 ? e.id() : e.id() + " " + e.percent() + "%");
            warn(warnings, e);
        }
        if (held != null) {
            items.add("holding " + (held.percent() < 0 ? held.id() : held.id() + " " + held.percent() + "%"));
            warn(warnings, held);
        }
        List<String> out = new ArrayList<>();
        out.add("armor " + armorPoints);
        out.addAll(warnings);
        out.addAll(items);
        return out;
    }

    private static void warn(List<String> into, Entry e) {
        if (e.percent() >= 0 && e.percent() < WARN_BELOW_PERCENT) {
            String text = e.id() + " almost broken (" + e.percent() + "%)";
            if (!into.contains(text)) into.add(text);
        }
    }
}
