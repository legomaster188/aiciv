package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.time.Duration;

public sealed interface BackendResult {
    record Ok(JsonObject body) implements BackendResult {}

    /** retryAfter is null when the provider did not say how long to wait. */
    record RateLimited(Duration retryAfter) implements BackendResult {}

    /**
     * The provider refused the request as too large for it (HTTP 413, or a per-request token limit such as Groq's
     * tokens-per-minute cap). Not the provider's fault: a smaller request may go through at once.
     */
    record TooLarge(String reason) implements BackendResult {}

    /** auth is true when the key was rejected or missing. */
    record Failed(String reason, boolean auth) implements BackendResult {}
}
