package com.aiciv.llm;

import com.aiciv.brain.MenuOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TypeSafe AI's decision API (Jev): one choice from a fixed option list, with probabilities. Reachable directly
 * ({@code /v1/systemone}) or through OpenRouter ({@code /api/alpha/decisions}); the path is configurable.
 */
public final class TypesafeBackend {
    static final int MAX_OPTIONS = 255;
    private static final Logger LOG = LoggerFactory.getLogger("aiciv");

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final AtomicBoolean loggedFirst = new AtomicBoolean();

    public TypesafeBackend(String baseUrl, String decisionsPath, String apiKey, Duration timeout) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = decisionsPath == null || decisionsPath.isBlank() ? "/v1/systemone" : decisionsPath.trim();
        this.endpoint = URI.create(base + (path.startsWith("/") ? path : "/" + path));
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Posts the question; the Ok body is the raw JSON, which {@link #parse} reads. Status handling is the chat backend's. */
    public CompletableFuture<BackendResult> choose(String model, String state, List<MenuOption> options, String instructions) {
        if (options.isEmpty() || options.size() > MAX_OPTIONS) {
            return CompletableFuture.completedFuture(new BackendResult.Failed("a menu needs 1 to " + MAX_OPTIONS + " options", false));
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, state, options, instructions).toString()));
        if (apiKey != null) {
            try {
                req.header("Authorization", "Bearer " + apiKey);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(
                        new BackendResult.Failed("API key contains characters that cannot be sent", true));
            }
        }
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((resp, err) -> {
                    if (err != null) return (BackendResult) new BackendResult.Failed("network: " + describe(err), false);
                    BackendResult r = HttpChatBackend.interpret(resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After"), apiKey);
                    if (r instanceof BackendResult.Ok ok && loggedFirst.compareAndSet(false, true)) {
                        LOG.debug("First decision reply from {}: {}", endpoint, HttpChatBackend.redact(ok.body().toString(), apiKey));
                    }
                    return r;
                });
    }

    static JsonObject requestBody(String model, String state, List<MenuOption> options, String instructions) {
        JsonObject criteria = new JsonObject();
        for (MenuOption o : options) criteria.addProperty(o.id(), o.description());
        JsonObject action = new JsonObject();
        action.addProperty("type", "choice");
        action.addProperty("instructions", instructions);
        action.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("action", action);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("state", state);
        body.add("questions", questions);
        return body;
    }

    /**
     * The one place that knows the reply shape. Accepts {"answers": {"action": {...}}} or {"action": {...}}; the choice is
     * the first string among choice, answer, value, option.
     */
    public static MenuReply parse(JsonObject body) throws ReplyFormatException {
        JsonObject root = body;
        JsonElement answers = body.get("answers");
        if (answers != null && answers.isJsonObject()) root = answers.getAsJsonObject();
        JsonElement actionEl = root.get("action");
        if (actionEl == null || !actionEl.isJsonObject()) throw new ReplyFormatException("decision reply had no answer for 'action'");
        JsonObject action = actionEl.getAsJsonObject();
        String choice = null;
        for (String field : new String[] {"choice", "answer", "value", "option"}) {
            JsonElement e = action.get(field);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                choice = e.getAsString().trim();
                break;
            }
        }
        if (choice == null || choice.isEmpty()) throw new ReplyFormatException("decision reply named no option");
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonElement p = action.get("probabilities");
        if (p != null && p.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : p.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    probabilities.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
        }
        JsonElement c = action.get("confidence");
        Double confidence = c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber() ? c.getAsDouble() : null;
        return new MenuReply(choice, probabilities, confidence);
    }

    private static String describe(Throwable err) {
        Throwable t = err;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
