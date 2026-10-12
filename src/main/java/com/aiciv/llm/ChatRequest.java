package com.aiciv.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/** Builds an OpenAI-format chat-completions request body. */
public final class ChatRequest {
    private ChatRequest() {}

    public static JsonObject build(String model, String system, String user, JsonArray tools, int maxTokens, JsonObject extra) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools.deepCopy());
            body.addProperty("tool_choice", "auto");
        }
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.8);
        if (extra != null) {
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) body.add(e.getKey(), e.getValue().deepCopy());
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
