package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.GoalIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The known personalities: the shipped file merged with the server's {@code personalities.json}. Loading never throws; a
 * broken user file leaves the shipped personalities in place and is reported through {@link #problems()}.
 */
public final class Personalities {
    static final String RESOURCE = "assets/aiciv/autopilot/personalities.json";
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,24}");
    private static final List<String> TRAITS = List.of("brave", "greedy", "social", "tidy", "patient");
    private static final Personality BUILT_IN = new Personality("default", "Default", "An ordinary survivor.",
            Traits.NEUTRAL, Map.of(), Map.of());

    private final Map<String, Personality> byId;
    private final List<String> problems;

    private Personalities(Map<String, Personality> byId, List<String> problems) {
        this.byId = byId;
        this.problems = List.copyOf(problems);
    }

    /** Exactly these personalities, with no problems (tests and reloads that must drop one use this). */
    public static Personalities of(Collection<Personality> list) {
        Map<String, Personality> m = new LinkedHashMap<>();
        for (Personality p : list) m.put(p.id(), p);
        return new Personalities(m, List.of());
    }

    /** The shipped personalities. */
    public static Personalities defaults() {
        List<String> problems = new ArrayList<>();
        return build(readShipped(problems), problems);
    }

    /** The shipped personalities merged with {@code configDir/personalities.json}. Never throws; see {@link #problems()}. */
    public static Personalities load(Path configDir) {
        List<String> problems = new ArrayList<>();
        Raw raw = readShipped(problems);
        Path file = configDir == null ? null : configDir.resolve("personalities.json");
        if (file != null && Files.isRegularFile(file)) {
            try {
                JsonElement root;
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(r);
                }
                if (!root.isJsonObject()) throw new IllegalStateException("the top level must be an object");
                Raw merged = raw.copy();
                merge(merged, root.getAsJsonObject(), problems);
                raw = merged;
            } catch (IOException | RuntimeException e) {
                problems.add("personalities.json: " + e.getMessage());
            }
        }
        return build(raw, problems);
    }

    // ---------------------------------------------------------------- raw (merged but not yet resolved) data

    private static final class Raw {
        final Fields base = new Fields();
        final Map<String, Entry> entries = new LinkedHashMap<>();

        Raw copy() {
            Raw r = new Raw();
            r.base.putAll(base);
            for (Map.Entry<String, Entry> e : entries.entrySet()) r.entries.put(e.getKey(), e.getValue().copy());
            return r;
        }
    }

    private static final class Entry {
        String name, description;
        final Fields fields = new Fields();

        Entry copy() {
            Entry e = new Entry();
            e.name = name;
            e.description = description;
            e.fields.putAll(fields);
            return e;
        }
    }

    private static final class Fields {
        final Map<String, Double> traits = new LinkedHashMap<>();
        final Map<String, Double> goals = new LinkedHashMap<>();
        final Map<String, List<String>> phrases = new LinkedHashMap<>();

        void putAll(Fields o) {
            traits.putAll(o.traits);
            goals.putAll(o.goals);
            phrases.putAll(o.phrases);
        }
    }

    private static Raw readShipped(List<String> problems) {
        Raw raw = new Raw();
        try (InputStream in = Personalities.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                merge(raw, JsonParser.parseReader(r).getAsJsonObject(), problems);
            }
        } catch (IOException | RuntimeException e) {
            problems.add("personalities.json: shipped file unusable: " + e.getMessage());
            raw = new Raw();
        }
        if (!raw.entries.containsKey("default")) {
            Entry d = new Entry();
            d.name = BUILT_IN.name();
            d.description = BUILT_IN.description();
            raw.entries.put("default", d);
        }
        return raw;
    }

    /** Overlays one file's base and personalities onto {@code raw}; a bad entry is reported and skipped. */
    private static void merge(Raw raw, JsonObject root, List<String> problems) {
        JsonElement base = root.get("base");
        if (base != null) {
            if (base.isJsonObject()) readFields("base", base.getAsJsonObject(), raw.base, problems);
            else problems.add("personalities.json: 'base' must be an object");
        }
        JsonElement list = root.get("personalities");
        if (list == null) return;
        if (!list.isJsonObject()) {
            problems.add("personalities.json: 'personalities' must be an object");
            return;
        }
        for (Map.Entry<String, JsonElement> e : list.getAsJsonObject().entrySet()) {
            String id = e.getKey();
            if (!ID.matcher(id).matches()) {
                problems.add("personality '" + id + "': id must match [a-z0-9_]{1,24}");
                continue;
            }
            if (!e.getValue().isJsonObject()) {
                problems.add("personality '" + id + "': must be an object");
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            Entry entry = raw.entries.computeIfAbsent(id, k -> new Entry());
            if (o.has("name") && isString(o.get("name"))) entry.name = o.get("name").getAsString();
            if (o.has("description") && isString(o.get("description"))) entry.description = o.get("description").getAsString();
            readFields("personality '" + id + "'", o, entry.fields, problems);
        }
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static void readFields(String who, JsonObject o, Fields into, List<String> problems) {
        String prefix = who + ": ";
        JsonElement traits = o.get("traits");
        if (traits != null && traits.isJsonObject()) {
            for (Map.Entry<String, JsonElement> t : traits.getAsJsonObject().entrySet()) {
                if (!TRAITS.contains(t.getKey())) {
                    problems.add(prefix + "unknown trait '" + t.getKey() + "'");
                    continue;
                }
                Double v = number(t.getValue());
                if (v == null) {
                    problems.add(prefix + "trait '" + t.getKey() + "' is not a number");
                    continue;
                }
                double c = Traits.clamp(v);
                if (c != v) problems.add(prefix + "trait '" + t.getKey() + "' is " + v + "; clamped to " + c);
                into.traits.put(t.getKey(), c);
            }
        } else if (traits != null) {
            problems.add(prefix + "'traits' must be an object");
        }
        JsonElement goals = o.get("goals");
        if (goals != null && goals.isJsonObject()) {
            for (Map.Entry<String, JsonElement> g : goals.getAsJsonObject().entrySet()) {
                if (!GoalIds.ALL.contains(g.getKey())) {
                    problems.add(prefix + "unknown goal '" + g.getKey() + "'");
                    continue;
                }
                Double v = number(g.getValue());
                if (v == null) {
                    problems.add(prefix + "goal '" + g.getKey() + "' is not a number");
                    continue;
                }
                double c = Math.max(0, Math.min(1, v));
                if (c != v) problems.add(prefix + "goal '" + g.getKey() + "' is " + v + "; clamped to " + c);
                into.goals.put(g.getKey(), c);
            }
        } else if (goals != null) {
            problems.add(prefix + "'goals' must be an object");
        }
        JsonElement phrases = o.get("phrases");
        if (phrases != null && phrases.isJsonObject()) {
            for (Map.Entry<String, JsonElement> ph : phrases.getAsJsonObject().entrySet()) {
                if (!ph.getValue().isJsonArray()) {
                    problems.add(prefix + "phrases '" + ph.getKey() + "' must be a list of strings");
                    continue;
                }
                List<String> lines = new ArrayList<>();
                JsonArray arr = ph.getValue().getAsJsonArray();
                for (JsonElement line : arr) if (isString(line)) lines.add(line.getAsString());
                into.phrases.put(ph.getKey(), List.copyOf(lines));
            }
        } else if (phrases != null) {
            problems.add(prefix + "'phrases' must be an object");
        }
    }

    private static Double number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double d = p.getAsDouble();
        return Double.isFinite(d) ? d : null;
    }

    // ---------------------------------------------------------------- resolving

    private static Personalities build(Raw raw, List<String> problems) {
        Map<String, Personality> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : raw.entries.entrySet()) {
            String id = e.getKey();
            Entry entry = e.getValue();
            Fields f = new Fields();
            f.putAll(raw.base);
            f.putAll(entry.fields);
            Traits traits = new Traits(f.traits.getOrDefault("brave", 0.0), f.traits.getOrDefault("greedy", 0.0),
                    f.traits.getOrDefault("social", 0.0), f.traits.getOrDefault("tidy", 0.0), f.traits.getOrDefault("patient", 0.0));
            out.put(id, new Personality(id, entry.name == null ? id : entry.name, entry.description == null ? "" : entry.description,
                    traits, Collections.unmodifiableMap(f.goals), Collections.unmodifiableMap(f.phrases)));
        }
        return new Personalities(out, problems);
    }

    public Optional<Personality> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    public List<Personality> all() {
        return List.copyOf(byId.values());
    }

    public List<String> problems() {
        return problems;
    }

    /** The "default" personality (always present in loaded sets; a neutral built-in otherwise). */
    public Personality fallback() {
        return byId.getOrDefault("default", BUILT_IN);
    }
}
