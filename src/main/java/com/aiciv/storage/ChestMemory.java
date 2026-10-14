package com.aiciv.storage;

import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import com.aiciv.world.DimensionStores;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a bot last saw inside the containers it opened. Pure; the bot's own memory, so it can be out of date. One store
 * per dimension; every method but {@link #enterDimension} acts on the active one.
 */
public final class ChestMemory {
    public static final int CAPACITY = 16;
    private static final int KINDS_SHOWN = 4;

    private record Entry(Map<String, Integer> contents, long tick) {}

    private final DimensionStores<Map<Pos, Entry>> stores = new DimensionStores<>(LinkedHashMap::new);

    /** Positions only mean something in one dimension: switch to the chests remembered in {@code key}. */
    public void enterDimension(String key) {
        stores.enter(key);
    }

    /** Remembers {@code contents} (item id to count) of the container at {@code pos}; past capacity the least recently seen is dropped. */
    public void seen(Pos pos, Map<String, Integer> contents, long tick) {
        Map<Pos, Entry> entries = entries();
        entries.remove(pos);
        entries.put(pos, new Entry(Map.copyOf(contents), tick));
        while (entries.size() > CAPACITY) {
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

    public void forget(Pos pos) {
        entries().remove(pos);
    }

    public Optional<Map<String, Integer>> contents(Pos pos) {
        Entry e = entries().get(pos);
        return e == null ? Optional.empty() : Optional.of(e.contents());
    }

    public List<Pos> positions() {
        return new ArrayList<>(entries().keySet());
    }

    /** The nearest {@code n} remembered containers to {@code from}, one line each, nearest first. */
    public List<String> describe(Pos from, int n) {
        List<Pos> order = new ArrayList<>(entries().keySet());
        order.sort(Comparator.comparingLong((Pos p) -> distanceSquared(from, p)).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        List<String> out = new ArrayList<>();
        for (Pos p : order) {
            if (out.size() >= n) break;
            out.add(line(from, p, entries().get(p).contents()));
        }
        return out;
    }

    /** The item ids remembered in the container nearest to {@code from}, most plentiful first; empty when none is known. */
    public List<String> nearestKinds(Pos from) {
        return nearest(from).map(best -> byCount(entries().get(best).contents()).stream().map(Map.Entry::getKey).toList()).orElse(List.of());
    }

    /** The remembered container nearest to {@code from}: the one {@link #nearestKinds} describes. */
    public Optional<Pos> nearest(Pos from) {
        Pos best = null;
        for (Pos p : entries().keySet()) if (best == null || distanceSquared(from, p) < distanceSquared(from, best)) best = p;
        return Optional.ofNullable(best);
    }

    private Map<Pos, Entry> entries() {
        return stores.active();
    }

    private static String line(Pos from, Pos p, Map<String, Integer> contents) {
        int dx = p.x() - from.x(), dy = p.y() - from.y(), dz = p.z() - from.z();
        long metres = Math.round(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
        StringBuilder sb = new StringBuilder("chest at ").append(p.x()).append(' ').append(p.y()).append(' ').append(p.z())
                .append(" (").append(metres).append('m');
        String direction = Compass.direction(dx, dz);
        if (!direction.equals("here")) sb.append(' ').append(direction);
        sb.append("): ");
        if (contents.isEmpty()) return sb.append("empty").toString();
        List<Map.Entry<String, Integer>> kinds = byCount(contents);
        for (int i = 0; i < Math.min(KINDS_SHOWN, kinds.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(kinds.get(i).getKey()).append(" x").append(kinds.get(i).getValue());
        }
        if (kinds.size() > KINDS_SHOWN) sb.append(" (+").append(kinds.size() - KINDS_SHOWN).append(" more kinds)");
        return sb.toString();
    }

    private static List<Map.Entry<String, Integer>> byCount(Map<String, Integer> contents) {
        List<Map.Entry<String, Integer>> kinds = new ArrayList<>(contents.entrySet());
        kinds.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return kinds;
    }

    private static long distanceSquared(Pos a, Pos b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
