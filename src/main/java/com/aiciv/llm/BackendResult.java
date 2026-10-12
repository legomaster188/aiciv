package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.time.Duration;

public sealed interface BackendResult {
    record Ok(JsonObject body) implements BackendResult {}

    /** retryAfter is null when the provider did not say how long to wait. */
    record RateLimited(Duration retryAfter) implements BackendResult {}

    /** auth is true when the key was rejected or missing. */
    record Failed(String reason, boolean auth) implements BackendResult {}
}
