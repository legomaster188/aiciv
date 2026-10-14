package com.aiciv.autopilot.personality;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The known personalities. Task 1 stub: reads only "default" from the shipped file; Task 3 owns the body (keep
 * {@link #of}, which the game tests use to build a set without "default").
 */
public final class Personalities {
    static final String RESOURCE = "assets/aiciv/autopilot/personalities.json";
    private static final Personality BUILT_IN = new Personality("default", "Default", "An ordinary survivor.",
            Traits.NEUTRAL, Map.of(), Map.of());

    private final Map<String, Personality> byId;
    private final List<String> problems;

    private Personalities(Map<String, Personality> byId, List<String> problems) {
        this.byId = byId;
        this.problems = List.copyOf(problems);
    }

    /** Exactly these personalities, with no problems. */
    public static Personalities of(Collection<Personality> list) {
        Map<String, Personality> m = new LinkedHashMap<>();
        for (Personality p : list) m.put(p.id(), p);
        return new Personalities(m, List.of());
    }

    /** The shipped personalities. */
    public static Personalities defaults() {
        List<String> problems = new ArrayList<>();
        Map<String, Personality> m = new LinkedHashMap<>();
        m.put("default", readDefault(problems));
        return new Personalities(m, problems);
    }

    /** The shipped personalities merged with {@code configDir/personalities.json}. Never throws; see {@link #problems()}. */
    public static Personalities load(Path configDir) {
        return defaults();
    }

    private static Personality readDefault(List<String> problems) {
        try (InputStream in = Personalities.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject d = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("personalities").getAsJsonObject("default");
                return new Personality("default", d.get("name").getAsString(), d.get("description").getAsString(),
                        Traits.NEUTRAL, Map.of(), Map.of());
            }
        } catch (IOException | RuntimeException e) {
            problems.add("personalities.json: " + e.getMessage());
            return BUILT_IN;
        }
    }

    public Optional<Personality> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Set<String> ids() {
        return java.util.Collections.unmodifiableSet(byId.keySet());
    }

    public List<Personality> all() {
        return List.copyOf(byId.values());
    }

    public List<String> problems() {
        return problems;
    }

    /** The "default" personality. */
    public Personality fallback() {
        return byId.getOrDefault("default", BUILT_IN);
    }
}
