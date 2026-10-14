package com.aiciv.world;

import com.aiciv.nav.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a bot remembers of useful blocks it has seen: ores, logs, crafting stations, portals. Bounded; least recently seen
 * goes first. Plentiful kinds (logs, nether bricks, obsidian, rails) are capped per group so a forest or a fortress
 * wall cannot push ores and furnaces out of the cache. One store per dimension, the active one chosen by
 * {@link #enterDimension}; every other method acts on the active store.
 */
public final class KnownBlocks {
    static final int MAX_LOGS = 24;
    public static final int MAX_DIMENSIONS = DimensionStores.MAX_DIMENSIONS;

    private record Entry(String label, long tick) {}

    private final int capacity;
    /** Iteration order is the order entries were last seen, so ties on tick evict the oldest first. */
    private final DimensionStores<LinkedHashMap<Pos, Entry>> stores = new DimensionStores<>(LinkedHashMap::new);

    public KnownBlocks(int capacity) {
        this.capacity = capacity;
    }

    /** Switches to the memory of dimension {@code key} (a full id such as {@code minecraft:the_nether}). */
    public void enterDimension(String key) {
        stores.enter(key);
    }

    /** The cap group of a block label: all logs share {@code log}, all rails {@code rail}; anything else is its own group. */
    static String group(String label) {
        if (BlockNames.isLog(label)) return "log";
        return switch (label) {
            case "rail", "powered_rail", "detector_rail", "activator_rail" -> "rail";
            default -> label;
        };
    }

    static int cap(String group) {
        return switch (group) {
            case "log" -> MAX_LOGS;
            case "nether_bricks" -> 8;
            case "obsidian" -> 16;
            case "rail" -> 8;
            default -> Integer.MAX_VALUE;
        };
    }

    public void see(String label, Pos pos, long tick) {
        LinkedHashMap<Pos, Entry> entries = entries();
        entries.remove(pos);
        entries.put(pos, new Entry(label, tick));
        String group = group(label);
        int cap = cap(group);
        if (cap != Integer.MAX_VALUE) {
            Predicate<String> same = l -> group(l).equals(group);
            if (count(same) > cap) evictOldest(same);
        }
        if (entries.size() > capacity) evictOldest(l -> true);
    }

    public void gone(Pos pos) {
        entries().remove(pos);
    }

    public Optional<Pos> nearest(Predicate<String> label, Pos from) {
        Pos best = null;
        long bestD2 = Long.MAX_VALUE;
        for (Map.Entry<Pos, Entry> e : entries().entrySet()) {
            if (!label.test(e.getValue().label())) continue;
            long d2 = distanceSquared(e.getKey(), from);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = e.getKey();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Up to {@code n} entries at least {@code minDistance} blocks from {@code from}, nearest first. */
    public List<Map.Entry<Pos, String>> nearestN(Pos from, int n, double minDistance) {
        double min2 = minDistance * minDistance;
        List<Map.Entry<Pos, String>> out = new ArrayList<>();
        for (Map.Entry<Pos, Entry> e : entries().entrySet()) {
            if (distanceSquared(e.getKey(), from) >= min2) out.add(Map.entry(e.getKey(), e.getValue().label()));
        }
        out.sort(Comparator.comparingLong(e -> distanceSquared(e.getKey(), from)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** Entries in the active dimension's store. */
    public int size() {
        return entries().size();
    }

    private LinkedHashMap<Pos, Entry> entries() {
        return stores.active();
    }

    private int count(Predicate<String> label) {
        int n = 0;
        for (Entry e : entries().values()) if (label.test(e.label())) n++;
        return n;
    }

    private void evictOldest(Predicate<String> label) {
        Pos oldest = null;
        long oldestTick = Long.MAX_VALUE;
        for (Map.Entry<Pos, Entry> e : entries().entrySet()) {
            if (label.test(e.getValue().label()) && e.getValue().tick() < oldestTick) {
                oldestTick = e.getValue().tick();
                oldest = e.getKey();
            }
        }
        if (oldest != null) entries().remove(oldest);
    }

    private static long distanceSquared(Pos a, Pos b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
