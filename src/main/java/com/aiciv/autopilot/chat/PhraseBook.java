package com.aiciv.autopilot.chat;

import com.aiciv.AiCiv;
import com.aiciv.autopilot.personality.Personality;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The chat lines one personality can say, by category. */
public final class PhraseBook {
    static final String RESOURCE = "assets/aiciv/autopilot/phrases.json";

    /** Category to the placeholders a cue of that category supplies (besides {@code me} and {@code personality}). */
    public static final Map<String, Set<String>> CATEGORIES = categories();

    private static volatile JsonObject shipped;

    private final Map<String, List<String>> lines;

    private PhraseBook(Map<String, List<String>> lines) {
        this.lines = lines;
    }

    private static Map<String, Set<String>> categories() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("greet", Set.of("other"));
        m.put("brag", Set.of("item"));
        m.put("complain", Set.of("attacker"));
        m.put("ask_help", Set.of("goal", "step", "item"));
        m.put("died", Set.of("message"));
        m.put("other_died", Set.of("dead", "message"));
        m.put("mention", Set.of("speaker"));
        m.put("banter", Set.of("other", "otherModel"));
        m.put("night", Set.of());
        m.put("goal", Set.of("goal"));
        m.put("give", Set.of("speaker", "item", "count"));
        m.put("refuse", Set.of("speaker", "item", "count"));
        m.put("coming", Set.of("speaker", "item", "count"));
        m.put("busy", Set.of("speaker", "item", "count"));
        m.put("thanks", Set.of("speaker"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** base, then phrases.json[id], then the personality's own phrases; a later level replaces a category outright. */
    public static PhraseBook forPersonality(Personality p) {
        JsonObject file = shipped();
        Map<String, List<String>> merged = new HashMap<>();
        overlay(merged, file.get("base"));
        overlay(merged, file.get(p.id()));
        if (p.phrases() != null) {
            p.phrases().forEach((category, list) -> {
                if (list != null) merged.put(category, List.copyOf(list));
            });
        }
        return new PhraseBook(merged);
    }

    public List<String> lines(String category) {
        return lines.getOrDefault(category, List.of());
    }

    /** The ids that have their own section in the shipped file. */
    static Set<String> shippedIds() {
        Set<String> ids = new java.util.TreeSet<>(shipped().keySet());
        ids.remove("version");
        ids.remove("base");
        return ids;
    }

    /** The lines the shipped file gives that id for the category itself (no merging); empty when it has none. */
    static List<String> ownLines(String id, String category) {
        Map<String, List<String>> own = new HashMap<>();
        overlay(own, shipped().get(id));
        return own.getOrDefault(category, List.of());
    }

    private static void overlay(Map<String, List<String>> into, JsonElement section) {
        if (section == null || !section.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : section.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            JsonArray array = e.getValue().getAsJsonArray();
            List<String> list = new java.util.ArrayList<>();
            for (JsonElement line : array) if (line.isJsonPrimitive()) list.add(line.getAsString());
            into.put(e.getKey(), List.copyOf(list));
        }
    }

    private static JsonObject shipped() {
        JsonObject s = shipped;
        if (s == null) {
            s = read();
            shipped = s;
        }
        return s;
    }

    private static JsonObject read() {
        try (InputStream in = PhraseBook.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            AiCiv.LOG.warn("Could not read the shipped autopilot phrases: {}", e.getMessage());
            return new JsonObject();
        }
    }
}
