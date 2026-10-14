package com.aiciv.bot;

import com.aiciv.autopilot.ChatPort;
import com.aiciv.autopilot.Situation;
import com.aiciv.llm.LlmService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** An autopilot's chat port over the bot manager. Server thread. */
final class LiveChatPort implements ChatPort {
    private final BotManager m;
    private final Bot b;

    LiveChatPort(BotManager m, Bot b) {
        this.m = m;
        this.b = b;
    }

    @Override
    public String botName() {
        return b.name;
    }

    @Override
    public String modelOf(String botName) {
        return m.modelOf(botName);
    }

    @Override
    public Situation situation() {
        return b.autopilot == null ? null : b.autopilot.lastSituation();
    }

    @Override
    public void say(String text) {
        if (!b.dead()) m.botSays(b, text);
    }

    @Override
    public boolean globalGapOk(long nowMillis) {
        return nowMillis - m.scheduler().lastAutopilotChatMillis >= m.config().autopilot.globalChatGapMillis;
    }

    @Override
    public void spoke(long nowMillis) {
        m.scheduler().lastAutopilotChatMillis = nowMillis;
    }

    @Override
    public boolean llmReady(String modelKey) {
        return m.llm().peek(modelKey).isPresent();
    }

    @Override
    public Optional<CompletableFuture<LlmService.Outcome>> sendLlm(String modelKey, String system, String user) {
        return m.llm().trySend(modelKey, system, user, new JsonArray());
    }

    @Override
    public void onServer(Runnable r) {
        m.server().execute(r);
    }

    @Override
    public void log(JsonObject record) {
        m.decisionLog().append(b.name, record);
    }
}
