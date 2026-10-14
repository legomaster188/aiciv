package com.aiciv.autopilot;

import com.aiciv.llm.LlmService;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** What an autopilot's chat may do in the game; the live version wraps the bot manager. Server thread. */
public interface ChatPort {
    String botName();

    /** The model of that online bot, or null when the name is not an online bot. */
    String modelOf(String botName);

    /** The last situation captured for this bot, or null. */
    Situation situation();

    /** Says the line in chat as this bot. */
    void say(String text);

    /** Whether enough time passed since any autopilot spoke. */
    boolean globalGapOk(long nowMillis);

    /** Records that an autopilot spoke now. */
    void spoke(long nowMillis);

    /** Whether the LLM model has a route free now (spends no budget). */
    boolean llmReady(String modelKey);

    /** Sends a plain chat request (no tools); empty when no route can take it now. */
    Optional<CompletableFuture<LlmService.Outcome>> sendLlm(String modelKey, String system, String user);

    /** Runs on the server thread. */
    void onServer(Runnable r);

    /** Appends to this bot's decision log. */
    void log(JsonObject record);
}
