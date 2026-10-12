package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Sends one chat-completions request body. Never completes exceptionally for HTTP errors. */
public interface ChatBackend {
    CompletableFuture<BackendResult> send(JsonObject requestBody);
}
