package com.aiciv.autopilot.chat;

import com.aiciv.autopilot.ChatPort;
import com.aiciv.autopilot.Situation;
import com.aiciv.llm.LlmService;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** A chat port with a settable clock that records what was said and logged. */
final class FakeChatPort implements ChatPort {
    String name = "Ada";
    final Map<String, String> models = new HashMap<>();
    Situation situation;
    final List<String> said = new ArrayList<>();
    final List<JsonObject> logs = new ArrayList<>();
    final List<String> sentUsers = new ArrayList<>();
    final List<String> sentSystems = new ArrayList<>();
    boolean llmReady;
    /** What {@link #sendLlm} returns; empty means no route could take it. */
    Optional<CompletableFuture<LlmService.Outcome>> nextSend = Optional.empty();
    int sends;
    long gapMillis = 2000;
    long lastSpoke = Long.MIN_VALUE / 4;

    @Override public String botName() { return name; }
    @Override public String modelOf(String botName) { return models.get(botName); }
    @Override public Situation situation() { return situation; }
    @Override public void say(String text) { said.add(text); }
    @Override public boolean globalGapOk(long nowMillis) { return nowMillis - lastSpoke >= gapMillis; }
    @Override public void spoke(long nowMillis) { lastSpoke = nowMillis; }
    @Override public boolean llmReady(String modelKey) { return llmReady; }

    @Override
    public Optional<CompletableFuture<LlmService.Outcome>> sendLlm(String modelKey, String system, String user) {
        sends++;
        sentSystems.add(system);
        sentUsers.add(user);
        return nextSend;
    }

    @Override public void onServer(Runnable r) { r.run(); }
    @Override public void log(JsonObject record) { logs.add(record); }

    String logField(int i, String field) {
        return logs.get(i).get(field).isJsonNull() ? null : logs.get(i).get(field).getAsString();
    }
}
