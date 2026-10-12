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
        if (apiKey != null) req.header("Authorization", "Bearer " + apiKey);
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> err != null
                        ? new BackendResult.Failed("network: " + describe(err), false)
                        : interpret(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After")));
    }

    static BackendResult interpret(int status, String body, Optional<String> retryAfter) {
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
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
        // Gemini answers HTTP 400 "API key not valid" for a bad key.
        boolean badKey = status == 401 || status == 403 || (status == 400 && snippet.contains("API key"));
        if (badKey) return new BackendResult.Failed("HTTP " + status + " (key rejected): " + snippet, true);
        return new BackendResult.Failed("HTTP " + status + ": " + snippet, false);
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
