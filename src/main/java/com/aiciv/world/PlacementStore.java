package com.aiciv.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who placed which block where, per dimension, keyed by {@code BlockPos.asLong()}. Pure: no world access. Bounded: beyond
 * the capacity the oldest insertion is forgotten.
 */
public final class PlacementStore {
    public record Placement(String block, String placer, long tick) {}

    private record Key(String dimension, long pos) {}

    private final int capacity;
    /** Insertion order, oldest first; a re-put moves an entry to the end. */
    private final LinkedHashMap<Key, Placement> entries = new LinkedHashMap<>();
    private boolean dirty;

    public PlacementStore(int capacity) {
        this.capacity = capacity;
    }

    public void put(String dimension, long pos, Placement p) {
        Key key = new Key(dimension, pos);
        entries.remove(key);
        entries.put(key, p);
        dirty = true;
        Iterator<Key> oldest = entries.keySet().iterator();
        while (entries.size() > capacity && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    public Optional<Placement> get(String dimension, long pos) {
        return Optional.ofNullable(entries.get(new Key(dimension, pos)));
    }

    public void remove(String dimension, long pos) {
        if (entries.remove(new Key(dimension, pos)) != null) dirty = true;
    }

    /** Positions in this dimension placed by {@code placer} at or after {@code sinceTick}, oldest first. */
    public List<Long> placedBy(String dimension, String placer, long sinceTick) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Key, Placement> e : entries.entrySet()) {
            Placement p = e.getValue();
            if (e.getKey().dimension().equals(dimension) && p.placer().equals(placer) && p.tick() >= sinceTick) {
                out.add(e.getKey().pos());
            }
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** {"<dimension>": {"<pos long>": ["block", "placer", tick], ...}, ...}. */
    public String toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<Key, Placement> e : entries.entrySet()) {
            String dimension = e.getKey().dimension();
            if (!root.has(dimension)) root.add(dimension, new JsonObject());
            JsonArray value = new JsonArray();
            value.add(e.getValue().block());
            value.add(e.getValue().placer());
            value.add(e.getValue().tick());
            root.getAsJsonObject(dimension).add(Long.toString(e.getKey().pos()), value);
        }
        return root.toString();
    }

    /** Reads {@link #toJson()} output, skipping malformed entries; the oldest ticks are dropped first when over capacity. */
    public static PlacementStore fromJson(String json, int capacity) {
        record Loaded(String dimension, long pos, Placement placement) {}
        List<Loaded> loaded = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed.isJsonObject()) {
            for (Map.Entry<String, JsonElement> dim : parsed.getAsJsonObject().entrySet()) {
                if (!dim.getValue().isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> e : dim.getValue().getAsJsonObject().entrySet()) {
                    try {
                        JsonArray v = e.getValue().getAsJsonArray();
                        loaded.add(new Loaded(dim.getKey(), Long.parseLong(e.getKey()),
                                new Placement(v.get(0).getAsString(), v.get(1).getAsString(), v.get(2).getAsLong())));
                    } catch (RuntimeException ignored) {
                        // One bad entry (hand-edited file) must not lose the rest.
                    }
                }
            }
        }
        loaded.sort(Comparator.comparingLong(l -> l.placement().tick()));
        PlacementStore store = new PlacementStore(capacity);
        for (Loaded l : loaded) store.put(l.dimension(), l.pos(), l.placement());
        store.markClean();
        return store;
    }
}
