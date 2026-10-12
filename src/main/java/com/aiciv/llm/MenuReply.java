package com.aiciv.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A menu-mode decision: the option id the model chose, plus the probabilities and confidence when it reports them. */
public record MenuReply(String optionId, Map<String, Double> probabilities, Double confidence) {
    /**
     * Reads a chat model's answer to the menu: a {@code choose} tool call, JSON {"option": "id"}, or a bare id in the text.
     * Throws when the reply names nothing at all.
     */
    public static MenuReply fromChat(LlmReply reply, Collection<String> ids) throws ReplyFormatException {
        for (ToolCall c : reply.calls()) {
            if (!"choose".equalsIgnoreCase(c.name())) continue;
            JsonElement o = c.args().get("option");
            if (o != null && o.isJsonPrimitive()) return new MenuReply(o.getAsString().trim(), Map.of(), null);
        }
        String text = reply.plainText();
        if (text == null || text.isBlank()) throw new ReplyFormatException("reply did not choose an option");
        String id = optionFromJson(text);
        if (id == null) id = bareId(text, ids);
        return new MenuReply(id, Map.of(), null);
    }

    private static String optionFromJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            JsonElement el = JsonParser.parseString(text.substring(start, end + 1));
            JsonElement o = el.isJsonObject() ? ((JsonObject) el).get("option") : null;
            return o != null && o.isJsonPrimitive() ? o.getAsString().trim() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** The text itself when it is an id; otherwise the longest known id that appears in it as a whole word. */
    private static String bareId(String text, Collection<String> ids) {
        String lower = text.toLowerCase(Locale.ROOT);
        String cleaned = lower.trim().replaceAll("^[\\s`'\"*]+|[\\s`'\".!*]+$", "");
        if (ids.contains(cleaned)) return cleaned;
        return ids.stream()
                .filter(id -> Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(id) + "(?![a-z0-9_])").matcher(lower).find())
                .max(Comparator.comparingInt(String::length))
                .orElse(cleaned);
    }
}
