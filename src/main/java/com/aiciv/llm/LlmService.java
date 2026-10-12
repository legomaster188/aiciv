package com.aiciv.llm;

import com.aiciv.config.AiCivConfig;
import com.aiciv.config.AiCivConfig.ModelConfig;
import com.aiciv.config.AiCivConfig.ProviderConfig;
import com.aiciv.config.KeyStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Picks a healthy provider with budget for a model (following fallbacks) and sends the request. */
public final class LlmService {
    public record Route(String modelKey, String providerKey, String modelId) {}

    public sealed interface Outcome {
        Route route();

        record Reply(Route route, LlmReply reply) implements Outcome {}
        record RateLimited(Route route) implements Outcome {}
        record Failed(Route route, String reason) implements Outcome {}
    }

    private final AiCivConfig config;
    private final Map<String, ChatBackend> backends;
    private final Map<String, RateLimiter> limiters = new HashMap<>();
    private final Map<String, ProviderHealth> health = new HashMap<>();

    public LlmService(AiCivConfig config, Map<String, ChatBackend> backends, Clock clock) {
        this.config = config;
        this.backends = Map.copyOf(backends);
        for (Map.Entry<String, ProviderConfig> e : config.providers.entrySet()) {
            ProviderConfig p = e.getValue();
            limiters.put(e.getKey(), new RateLimiter(p.rpm, p.rpd, p.maxInFlight, zone(p.resetZone), clock));
            health.put(e.getKey(), new ProviderHealth(clock));
        }
        for (String provider : config.providers.keySet()) {
            if (!this.backends.containsKey(provider)) health.get(provider).onAuthFailure("no backend configured");
        }
    }

    /** Builds HTTP or scripted backends from config. Providers missing a key start disabled. */
    public static LlmService create(AiCivConfig config, KeyStore keys, Path configDir, Clock clock) {
        Map<String, ChatBackend> backends = new HashMap<>();
        Map<String, String> disabled = new HashMap<>();
        for (Map.Entry<String, ProviderConfig> e : config.providers.entrySet()) {
            ProviderConfig p = e.getValue();
            if ("scripted".equals(p.type)) {
                try {
                    backends.put(e.getKey(), ScriptedBackend.fromFile(configDir.resolve(p.script)));
                } catch (IOException | RuntimeException ex) {
                    disabled.put(e.getKey(), "cannot read script " + p.script + ": " + ex.getMessage());
                }
                continue;
            }
            String key = null;
            if (p.key != null) {
                Optional<String> k = keys.get(p.key);
                if (k.isEmpty()) disabled.put(e.getKey(), "no key '" + p.key + "' in keys.json or " + KeyStore.envName(p.key));
                key = k.orElse(null);
            }
            try {
                backends.put(e.getKey(), new HttpChatBackend(p.baseUrl, key, Duration.ofSeconds(30)));
            } catch (RuntimeException ex) {
                disabled.put(e.getKey(), "invalid baseUrl '" + p.baseUrl + "': " + ex.getMessage());
            }
        }
        LlmService service = new LlmService(config, backends, clock);
        disabled.forEach((provider, reason) -> service.health.get(provider).onAuthFailure(reason));
        return service;
    }

    public boolean knowsModel(String modelKey) {
        return !routes(modelKey).isEmpty();
    }

    public Set<String> modelKeys() {
        return config.models.keySet();
    }

    /** The first route that would take a request right now, without using any budget. */
    public Optional<Route> peek(String modelKey) {
        for (Route r : routes(modelKey)) {
            if (health.get(r.providerKey()).available() && limiters.get(r.providerKey()).wouldAllow()) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** Sends through the first available route, or returns empty when none can take a request now. */
    public Optional<CompletableFuture<Outcome>> trySend(String modelKey, String system, String user, JsonArray tools) {
        for (Route r : routes(modelKey)) {
            ProviderHealth h = health.get(r.providerKey());
            RateLimiter limiter = limiters.get(r.providerKey());
            if (!h.available() || !limiter.tryAcquire()) continue;
            ModelConfig m = config.models.get(r.modelKey());
            CompletableFuture<BackendResult> sent;
            try {
                JsonObject body = ChatRequest.build(r.modelId(), system, user, tools, m.maxTokens, m.extra);
                sent = backends.get(r.providerKey()).send(body);
            } catch (RuntimeException e) {
                // Nothing left the machine: free the slot and count a failure, so a bad URL cannot wedge the provider.
                limiter.release();
                String reason = "could not send request: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                h.onFailure(reason);
                return Optional.of(CompletableFuture.completedFuture(new Outcome.Failed(r, reason)));
            }
            CompletableFuture<Outcome> f = sent.handle((result, err) -> {
                limiter.release();
                return settle(r, h, result, err);
            });
            return Optional.of(f);
        }
        return Optional.empty();
    }

    /** Provider keys on the model's route, primary first, following fallbacks. */
    public List<String> routeProviders(String modelKey) {
        return routes(modelKey).stream().map(Route::providerKey).toList();
    }

    /** Human-readable health of one provider, as shown by /ai budget (e.g. "disabled: no key"). */
    public String providerStatus(String providerKey) {
        ProviderHealth h = health.get(providerKey);
        return h == null ? "unknown provider" : h.status();
    }

    /** True when the provider is neither disabled nor paused, whatever its rate-limit budget. */
    public boolean providerAvailable(String providerKey) {
        ProviderHealth h = health.get(providerKey);
        return h != null && h.available();
    }

    /** True when the provider's key is missing or was rejected. */
    public boolean providerDisabled(String providerKey) {
        ProviderHealth h = health.get(providerKey);
        return h != null && h.isAuthDisabled();
    }

    public List<String> budgetLines() {
        List<String> lines = new ArrayList<>();
        for (String p : config.providers.keySet()) {
            RateLimiter l = limiters.get(p);
            int remaining = l.remainingToday();
            lines.add(p + ": " + l.usedToday() + " used today, "
                    + (remaining < 0 ? "no daily limit" : remaining + " left") + ", " + health.get(p).status());
        }
        return lines;
    }

    private List<Route> routes(String modelKey) {
        List<Route> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String key = modelKey;
        while (key != null && seen.add(key)) {
            ModelConfig m = config.models.get(key);
            if (m == null || !limiters.containsKey(m.provider)) break;
            out.add(new Route(key, m.provider, m.model));
            key = m.fallback;
        }
        return out;
    }

    private static Outcome settle(Route r, ProviderHealth h, BackendResult result, Throwable err) {
        if (err != null) {
            h.onFailure("internal error: " + err);
            return new Outcome.Failed(r, String.valueOf(err));
        }
        if (result instanceof BackendResult.Ok ok) {
            try {
                LlmReply reply = ReplyParser.parse(ok.body());
                h.onSuccess();
                return new Outcome.Reply(r, reply);
            } catch (ReplyFormatException e) {
                h.onFailure(e.getMessage());
                return new Outcome.Failed(r, e.getMessage());
            }
        }
        if (result instanceof BackendResult.RateLimited rl) {
            h.onRateLimited(rl.retryAfter());
            return new Outcome.RateLimited(r);
        }
        BackendResult.Failed f = (BackendResult.Failed) result;
        if (f.auth()) h.onAuthFailure(f.reason());
        else h.onFailure(f.reason());
        return new Outcome.Failed(r, f.reason());
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
