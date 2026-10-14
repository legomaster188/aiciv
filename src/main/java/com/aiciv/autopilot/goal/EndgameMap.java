package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import com.aiciv.llm.ToolCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The endgame steps, as data: for each step the action names that may do it, in order of preference. Milestone 4's action
 * names live only in {@code endgame.json}; the first candidate the build knows is used.
 */
public final class EndgameMap {
    private EndgameMap() {}

    public record Candidate(String action, JsonObject args) {}

    public record Entry(String id, EndgameCondition doneWhen, String flag, List<Candidate> candidates) {}

    private static volatile List<Entry> cached;

    /** The entries in file order; empty when the resource is missing or unreadable. */
    public static List<Entry> load() {
        List<Entry> loaded = cached;
        if (loaded == null) cached = loaded = read();
        return loaded;
    }

    private static List<Entry> read() {
        try (InputStream in = EndgameMap.class.getResourceAsStream("/assets/aiciv/autopilot/endgame.json")) {
            if (in == null) return List.of();
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Entry> out = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("steps")) {
                JsonObject o = el.getAsJsonObject();
                List<Candidate> candidates = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("candidates")) {
                    JsonObject co = c.getAsJsonObject();
                    candidates.add(new Candidate(co.get("action").getAsString(), co.getAsJsonObject("args")));
                }
                out.add(new Entry(o.get("id").getAsString(), EndgameCondition.valueOf(o.get("doneWhen").getAsString()),
                        o.has("flag") ? o.get("flag").getAsString() : null, List.copyOf(candidates)));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public static Step toStep(Entry e) {
        return new Step() {
            @Override public String id() { return e.id(); }
            @Override public boolean done(Situation s) { return e.doneWhen().test(s); }
            @Override public String flag() { return e.flag(); }
            @Override public Set<String> needs() { return Set.of(); }

            @Override public Move next(Situation s) {
                for (Candidate c : e.candidates()) {
                    if (s.hasAction(c.action())) return Move.act(e.id() + " with " + c.action(), ToolCall.of(c.action(), fill(c.args(), s)));
                }
                List<String> names = e.candidates().stream().map(Candidate::action).toList();
                return Move.blocked("needs one of " + String.join(", ", names) + " (not available yet)");
            }
        };
    }

    /** A copy of the args where "{x}", "{y}" and "{z}" become the bot's whole-number position. */
    static JsonObject fill(JsonObject args, Situation s) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> en : args.entrySet()) {
            JsonElement v = en.getValue();
            String text = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : "";
            switch (text) {
                case "{x}" -> out.addProperty(en.getKey(), s.x());
                case "{y}" -> out.addProperty(en.getKey(), s.y());
                case "{z}" -> out.addProperty(en.getKey(), s.z());
                default -> out.add(en.getKey(), v.deepCopy());
            }
        }
        return out;
    }
}
