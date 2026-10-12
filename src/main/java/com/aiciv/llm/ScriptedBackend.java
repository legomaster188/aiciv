package com.aiciv.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Canned replies for tests and dev runs. Each entry is an assistant message object
 * ({"content": ..., "tool_calls": [...]}). Once the script runs out it replies with an empty message.
 */
public final class ScriptedBackend implements ChatBackend {
    private final Deque<JsonObject> messages;
    private final List<JsonObject> requests = new ArrayList<>();

    public ScriptedBackend(List<JsonObject> assistantMessages) {
        this.messages = new ArrayDeque<>(assistantMessages);
    }

    public static ScriptedBackend fromFile(Path file) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (!root.isJsonArray()) throw new IOException(file + " must hold a JSON array of assistant messages");
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) throw new IOException(file + " has a non-object entry");
            list.add(el.getAsJsonObject());
        }
        return new ScriptedBackend(list);
    }

    @Override
    public synchronized CompletableFuture<BackendResult> send(JsonObject requestBody) {
        requests.add(requestBody.deepCopy());
        JsonObject message = messages.isEmpty() ? new JsonObject() : messages.poll();
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return CompletableFuture.completedFuture(new BackendResult.Ok(response));
    }

    public synchronized List<JsonObject> requests() {
        return List.copyOf(requests);
    }
}
