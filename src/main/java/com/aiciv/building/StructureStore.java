package com.aiciv.building;

import com.aiciv.nav.Pos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The named structures of one world. Pure; {@link StructureRegistry} puts it on disk. */
public final class StructureStore {
    private final Map<String, Structure> byName = new LinkedHashMap<>();
    private boolean dirty;

    public Optional<Structure> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(key(name)));
    }

    /** False when a structure with that name (in any case) exists. */
    public boolean add(Structure s) {
        if (byName.putIfAbsent(key(s.name()), s) != null) return false;
        dirty = true;
        return true;
    }

    /** Forgets a structure; for tests that rebuild under a fixed name in a world that outlives the run. */
    public boolean remove(String name) {
        if (name == null || byName.remove(key(name)) == null) return false;
        dirty = true;
        return true;
    }

    /**
     * Forgets the structure only when the entry under that name is exactly this one: the same spelling, owner, dimension
     * and origin. A bot taking down its own temporary build never drops someone else's entry that happens to share the
     * name in another case, place or dimension.
     */
    public boolean removeExact(String name, String owner, String dimension, Pos origin) {
        Structure s = name == null ? null : byName.get(key(name));
        if (s == null || !s.name().equals(name) || !s.owner().equals(owner) || !s.dimension().equals(dimension) || !s.origin().equals(origin)) {
            return false;
        }
        byName.remove(key(name));
        dirty = true;
        return true;
    }

    /** Up to {@code n} structures in the dimension, nearest origin first. */
    public List<Structure> nearest(String dimension, Pos from, int n) {
        List<Structure> out = new ArrayList<>();
        for (Structure s : byName.values()) if (s.dimension().equals(dimension)) out.add(s);
        out.sort(Comparator.comparingDouble((Structure s) -> distanceSq(s.origin(), from)).thenComparing(Structure::name));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    public boolean dirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public String toJson() {
        JsonArray all = new JsonArray();
        for (Structure s : byName.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name());
            o.addProperty("owner", s.owner());
            o.addProperty("dimension", s.dimension());
            o.add("origin", pos(s.origin()));
            o.add("min", pos(s.min()));
            o.add("max", pos(s.max()));
            o.addProperty("kind", s.kind());
            o.addProperty("builtTick", s.builtTick());
            all.add(o);
        }
        return all.toString();
    }

    /** Skips entries that cannot be read; the result starts clean. */
    public static StructureStore fromJson(String json) {
        StructureStore store = new StructureStore();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return store;
        for (JsonElement e : root.getAsJsonArray()) {
            try {
                JsonObject o = e.getAsJsonObject();
                Structure s = new Structure(o.get("name").getAsString(), o.get("owner").getAsString(), o.get("dimension").getAsString(),
                        pos(o.getAsJsonArray("origin")), pos(o.getAsJsonArray("min")), pos(o.getAsJsonArray("max")),
                        o.get("kind").getAsString(), o.get("builtTick").getAsLong());
                store.byName.putIfAbsent(key(s.name()), s);
            } catch (RuntimeException skip) {
                // A damaged entry is dropped; the rest still load.
            }
        }
        store.dirty = false;
        return store;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static double distanceSq(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static JsonArray pos(Pos p) {
        JsonArray a = new JsonArray();
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    private static Pos pos(JsonArray a) {
        if (a.size() != 3) throw new IllegalArgumentException("a position has three numbers");
        return new Pos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
}
