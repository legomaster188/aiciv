package com.aiciv.world;

import com.aiciv.nav.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** What a bot remembers of useful blocks it has seen: ores, logs, crafting stations. Bounded; least recently seen goes first. */
public final class KnownBlocks {
    private record Entry(String label, long tick) {}

    private final int capacity;
    /** Iteration order is the order entries were last seen, so ties on tick evict the oldest first. */
    private final LinkedHashMap<Pos, Entry> entries = new LinkedHashMap<>();

    public KnownBlocks(int capacity) {
        this.capacity = capacity;
    }

    public void see(String label, Pos pos, long tick) {
        entries.remove(pos);
        entries.put(pos, new Entry(label, tick));
        while (entries.size() > capacity) {
            Pos oldest = null;
            long oldestTick = Long.MAX_VALUE;
            for (Map.Entry<Pos, Entry> e : entries.entrySet()) {
                if (e.getValue().tick() < oldestTick) {
                    oldestTick = e.getValue().tick();
                    oldest = e.getKey();
                }
            }
            entries.remove(oldest);
        }
    }

    public void gone(Pos pos) {
        entries.remove(pos);
    }

    public Optional<Pos> nearest(Predicate<String> label, Pos from) {
        Pos best = null;
        long bestD2 = Long.MAX_VALUE;
        for (Map.Entry<Pos, Entry> e : entries.entrySet()) {
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
        for (Map.Entry<Pos, Entry> e : entries.entrySet()) {
            if (distanceSquared(e.getKey(), from) >= min2) out.add(Map.entry(e.getKey(), e.getValue().label()));
        }
        out.sort(Comparator.comparingLong(e -> distanceSquared(e.getKey(), from)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    public int size() {
        return entries.size();
    }

    private static long distanceSquared(Pos a, Pos b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
