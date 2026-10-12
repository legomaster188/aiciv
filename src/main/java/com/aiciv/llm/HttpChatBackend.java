package com.aiciv.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Any OpenAI-compatible chat-completions endpoint: Gemini, Groq, OpenRouter, OpenCode Zen, Ollama... */
public final class HttpChatBackend implements ChatBackend {
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public HttpChatBackend(String baseUrl, String apiKey, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public CompletableFuture<BackendResult> send(JsonObject body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null) {
            try {
                req.header("Authorization", "Bearer " + apiKey);
            } catch (IllegalArgumentException e) {
                // A key with CR, LF or other control characters cannot go in a header. Say so without sending.
                return CompletableFuture.completedFuture(
                        new BackendResult.Failed("API key contains characters that cannot be sent", true));
            }
        }
        // HttpRequest.timeout stops covering the exchange once headers arrive, so a stalled body needs its own bound.
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((resp, err) -> err != null
                        ? new BackendResult.Failed("network: " + describe(err), false)
                        : interpret(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After"), apiKey));
    }

    static BackendResult interpret(int status, String body, Optional<String> retryAfter, String apiKey) {
        if (status == 200) {
            try {
                JsonElement el = JsonParser.parseString(body);
                if (el.isJsonObject()) return new BackendResult.Ok(el.getAsJsonObject());
            } catch (JsonParseException ignored) {
                // reported below
            }
            return new BackendResult.Failed("provider sent a reply that is not JSON", false);
        }
        if (status == 429) {
            return new BackendResult.RateLimited(retryAfter.flatMap(HttpChatBackend::seconds).orElse(null));
        }
        String raw = body == null ? "" : body;
        // Gemini answers HTTP 400 "API key not valid" for a bad key. Classify on the provider's own words.
        boolean badKey = status == 401 || status == 403 || (status == 400 && snippet(raw).contains("API key"));
        // Redact before condensing or cutting, so no part of the key survives at the cut.
        String shown = snippet(redact(raw, apiKey));
        if (badKey) return new BackendResult.Failed("HTTP " + status + " (key rejected): " + shown, true);
        return new BackendResult.Failed("HTTP " + status + ": " + shown, false);
    }

    /** The text with every copy of the key replaced by ***. */
    static String redact(String text, String apiKey) {
        return apiKey == null || apiKey.isEmpty() ? text : text.replace(apiKey, "***");
    }

    /** Condenses whitespace and cuts to 200 characters. */
    private static String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static Optional<Duration> seconds(String value) {
        try {
            long s = Long.parseLong(value.trim());
            return s >= 0 ? Optional.of(Duration.ofSeconds(s)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String describe(Throwable err) {
        Throwable t = err;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
