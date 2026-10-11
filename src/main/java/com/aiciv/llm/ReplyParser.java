package com.aiciv.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Reads an OpenAI-style chat completion into actions, with a JSON-in-text fallback. */
public final class ReplyParser {
    private ReplyParser() {}

    public static LlmReply parse(JsonObject response) throws ReplyFormatException {
        JsonElement choicesEl = response.get("choices");
        if (choicesEl == null || !choicesEl.isJsonArray() || choicesEl.getAsJsonArray().isEmpty()) {
            throw new ReplyFormatException("reply had no choices");
        }
        JsonElement first = choicesEl.getAsJsonArray().get(0);
        JsonElement msgEl = first.isJsonObject() ? first.getAsJsonObject().get("message") : null;
        if (msgEl == null || !msgEl.isJsonObject()) throw new ReplyFormatException("reply had no message");
        JsonObject msg = msgEl.getAsJsonObject();

        String content = contentText(msg.get("content"));
        List<ToolCall> calls = new ArrayList<>();
        JsonElement toolCalls = msg.get("tool_calls");
        if (toolCalls != null && toolCalls.isJsonArray()) {
            for (JsonElement el : toolCalls.getAsJsonArray()) {
                ToolCall c = toolCall(el);
                if (c != null) calls.add(c);
            }
        }
        if (!calls.isEmpty()) return new LlmReply(calls, content.trim(), null);

        List<ToolCall> fromText = jsonFallback(content);
        if (!fromText.isEmpty()) return new LlmReply(fromText, "", null);

        String text = content.trim();
        return new LlmReply(List.of(), "", text.isEmpty() ? null : text);
    }

    private static String contentText(JsonElement c) {
        if (c == null || c.isJsonNull()) return "";
        if (c.isJsonPrimitive()) return c.getAsString();
        if (c.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : c.getAsJsonArray()) {
                JsonElement text = part.isJsonObject() ? part.getAsJsonObject().get("text") : null;
                if (text != null && text.isJsonPrimitive()) sb.append(text.getAsString());
            }
            return sb.toString();
        }
        return "";
    }

    private static ToolCall toolCall(JsonElement el) {
        if (!el.isJsonObject()) return null;
        JsonElement fnEl = el.getAsJsonObject().get("function");
        if (fnEl == null || !fnEl.isJsonObject()) return null;
        JsonObject fn = fnEl.getAsJsonObject();
        JsonElement name = fn.get("name");
        if (name == null || !name.isJsonPrimitive()) return null;
        return withArgs(name.getAsString(), fn.get("arguments"));
    }

    private static ToolCall withArgs(String name, JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return ToolCall.of(name, new JsonObject());
        if (raw.isJsonObject()) return ToolCall.of(name, raw.getAsJsonObject());
        if (raw.isJsonPrimitive()) {
            String s = raw.getAsString().trim();
            if (s.isEmpty()) return ToolCall.of(name, new JsonObject());
            try {
                JsonElement parsed = JsonParser.parseString(s);
                if (parsed.isJsonObject()) return ToolCall.of(name, parsed.getAsJsonObject());
            } catch (JsonParseException ignored) {
                // fall through to the error below
            }
        }
        return new ToolCall(name, new JsonObject(), "arguments for " + name + " were not a valid JSON object");
    }

    /**
     * Accepts [{"action": "say", "args": {...}}, ...] or one such object, optionally in a code fence.
     * Every '[' or '{' is tried in order, from the start of the text, so brackets in prose or in
     * argument values cannot hide the JSON that follows them.
     */
    static List<ToolCall> jsonFallback(String content) {
        String s = stripFence(content.trim());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '[' && c != '{') continue;
            List<ToolCall> calls = actionsAt(s, i);
            if (!calls.isEmpty()) return calls;
        }
        return List.of();
    }

    /** Reads the one JSON value starting at start and ignores any text after it. Empty unless every item is an action. */
    private static List<ToolCall> actionsAt(String s, int start) {
        JsonElement root;
        try {
            root = JsonParser.parseReader(new JsonReader(new StringReader(s.substring(start))));
        } catch (JsonParseException e) {
            return List.of();
        }
        List<JsonElement> items = new ArrayList<>();
        if (root.isJsonArray()) {
            root.getAsJsonArray().forEach(items::add);
        } else {
            items.add(root);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement item : items) {
            if (!item.isJsonObject()) return List.of();
            JsonElement action = item.getAsJsonObject().get("action");
            if (action == null || !action.isJsonPrimitive()) return List.of();
            calls.add(withArgs(action.getAsString(), item.getAsJsonObject().get("args")));
        }
        return calls;
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) return s;
        int firstNewline = s.indexOf('\n');
        String body = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
        int end = body.lastIndexOf("```");
        return end < 0 ? body : body.substring(0, end);
    }
}
