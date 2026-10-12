package com.aiciv.llm;

import com.aiciv.brain.MenuOption;
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
        /** A menu-mode answer; the option id may still be one the menu did not offer. */
        record Chosen(Route route, MenuReply reply) implements Outcome {}
        record RateLimited(Route route) implements Outcome {}
        record Failed(Route route, String reason) implements Outcome {}
    }

    private final AiCivConfig config;
    private final Map<String, ChatBackend> backends;
    /** Decision-only providers (type "typesafe"); they take menus, never chat requests. */
    private final Map<String, TypesafeBackend> menuBackends;
    private final Map<String, RateLimiter> limiters = new HashMap<>();
    private final Map<String, ProviderHealth> health = new HashMap<>();

    public LlmService(AiCivConfig config, Map<String, ChatBackend> backends, Clock clock) {
        this(config, backends, Map.of(), clock);
    }

    public LlmService(AiCivConfig config, Map<String, ChatBackend> backends, Map<String, TypesafeBackend> menuBackends, Clock clock) {
        this.config = config;
        this.backends = Map.copyOf(backends);
        this.menuBackends = Map.copyOf(menuBackends);
        for (Map.Entry<String, ProviderConfig> e : config.providers.entrySet()) {
            ProviderConfig p = e.getValue();
            limiters.put(e.getKey(), new RateLimiter(p.rpm, p.rpd, p.maxInFlight, zone(p.resetZone), clock));
            health.put(e.getKey(), new ProviderHealth(clock));
        }
        for (String provider : config.providers.keySet()) {
            if (!this.backends.containsKey(provider) && !this.menuBackends.containsKey(provider)) {
                health.get(provider).onAuthFailure("no backend configured");
            }
        }
    }

    /** Builds HTTP or scripted backends from config. Providers missing a key start disabled. */
    public static LlmService create(AiCivConfig config, KeyStore keys, Path configDir, Clock clock) {
        Map<String, ChatBackend> backends = new HashMap<>();
        Map<String, TypesafeBackend> menuBackends = new HashMap<>();
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
                if ("typesafe".equals(p.type)) {
                    menuBackends.put(e.getKey(), new TypesafeBackend(p.baseUrl, p.decisionsPath, key, timeout(p)));
                } else {
                    backends.put(e.getKey(), new HttpChatBackend(p.baseUrl, key, timeout(p)));
                }
            } catch (RuntimeException ex) {
                disabled.put(e.getKey(), "invalid baseUrl '" + p.baseUrl + "': " + ex.getMessage());
            }
        }
        LlmService service = new LlmService(config, backends, menuBackends, clock);
        disabled.forEach((provider, reason) -> service.health.get(provider).onAuthFailure(reason));
        return service;
    }

    private static Duration timeout(ProviderConfig p) {
        return Duration.ofSeconds(Math.max(5, p.timeoutSeconds));
    }

    /** The HTTP timeout {@link #create} gives the provider's backend. */
    public Duration timeoutFor(String providerKey) {
        return timeout(config.providers.get(providerKey));
    }

    public boolean knowsModel(String modelKey) {
        return !routes(modelKey).isEmpty();
    }

    public Set<String> modelKeys() {
        return config.models.keySet();
    }

    /**
     * The first route that would take a request right now, without using any budget. A tools-mode model sends chat
     * requests, which a decision-only provider never takes, so such a route is no route for it.
     */
    public Optional<Route> peek(String modelKey) {
        boolean menu = menuMode(modelKey);
        for (Route r : routes(modelKey)) {
            if (!menu && !backends.containsKey(r.providerKey())) continue;
            if (health.get(r.providerKey()).available() && limiters.get(r.providerKey()).wouldAllow()) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** Sends through the first available route, or returns empty when none can take a request now. */
    public Optional<CompletableFuture<Outcome>> trySend(String modelKey, String system, String user, JsonArray tools) {
        return dispatch(modelKey,
                r -> backends.get(r.providerKey()),
                r -> {
                    ModelConfig m = config.models.get(r.modelKey());
                    return backends.get(r.providerKey()).send(ChatRequest.build(r.modelId(), system, user, tools, m.maxTokens, m.extra));
                },
                (r, body) -> new Outcome.Reply(r, ReplyParser.parse(body)));
    }

    /** Whether the model decides by picking from an option menu (see {@link AiCivConfig#isMenuMode}). */
    public boolean menuMode(String modelKey) {
        return config.isMenuMode(modelKey);
    }

    /**
     * Sends a menu decision. A typesafe provider gets {@code user} as its state and {@code system} as its instructions;
     * a chat model gets the options listed after {@code user} and one forced {@code choose(option)} tool.
     */
    public Optional<CompletableFuture<Outcome>> trySendMenu(String modelKey, String system, String user, List<MenuOption> options) {
        List<String> ids = options.stream().map(MenuOption::id).toList();
        return dispatch(modelKey,
                r -> menuBackends.containsKey(r.providerKey()) ? menuBackends.get(r.providerKey()) : backends.get(r.providerKey()),
                r -> {
                    TypesafeBackend decider = menuBackends.get(r.providerKey());
                    if (decider != null) return decider.choose(r.modelId(), user, options, system);
                    ModelConfig m = config.models.get(r.modelKey());
                    StringBuilder list = new StringBuilder(user).append("\n\nYour options (call choose with one id):\n");
                    for (MenuOption o : options) list.append(o.id()).append(" - ").append(o.description()).append('\n');
                    JsonObject body = ChatRequest.build(r.modelId(), system, list.toString(), chooseTool(ids), m.maxTokens, m.extra);
                    if (!m.extra.has("tool_choice")) {
                        JsonObject forced = new JsonObject();
                        forced.addProperty("type", "function");
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", "choose");
                        forced.add("function", fn);
                        body.add("tool_choice", forced);
                    }
                    return backends.get(r.providerKey()).send(body);
                },
                (r, body) -> menuBackends.containsKey(r.providerKey())
                        ? new Outcome.Chosen(r, TypesafeBackend.parse(body))
                        : new Outcome.Chosen(r, MenuReply.fromChat(ReplyParser.parse(body), ids)));
    }

    private static JsonArray chooseTool(List<String> ids) {
        JsonArray enumIds = new JsonArray();
        ids.forEach(enumIds::add);
        JsonObject option = new JsonObject();
        option.addProperty("type", "string");
        option.addProperty("description", "The id of the option to do next.");
        option.add("enum", enumIds);
        JsonObject properties = new JsonObject();
        properties.add("option", option);
        JsonArray required = new JsonArray();
        required.add("option");
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);
        params.add("required", required);
        JsonObject function = new JsonObject();
        function.addProperty("name", "choose");
        function.addProperty("description", "Choose the one option to do next.");
        function.add("parameters", params);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        return tools;
    }

    private interface Sender {
        CompletableFuture<BackendResult> send(Route r);
    }

    private interface OkReader {
        Outcome read(Route r, JsonObject body) throws ReplyFormatException;
    }

    /** The first route with a usable backend, a healthy provider and budget sends; empty when none can take a request now. */
    private Optional<CompletableFuture<Outcome>> dispatch(String modelKey, java.util.function.Function<Route, Object> backendFor,
                                                          Sender sender, OkReader reader) {
        for (Route r : routes(modelKey)) {
            ProviderHealth h = health.get(r.providerKey());
            RateLimiter limiter = limiters.get(r.providerKey());
            if (backendFor.apply(r) == null) continue; // e.g. a decision-only provider asked for a chat request
            if (!h.available() || !limiter.tryAcquire()) continue;
            CompletableFuture<BackendResult> sent;
            try {
                sent = sender.send(r);
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
                return settle(r, h, result, err, reader);
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

    private static Outcome settle(Route r, ProviderHealth h, BackendResult result, Throwable err, OkReader reader) {
        if (err != null) {
            h.onFailure("internal error: " + err);
            return new Outcome.Failed(r, String.valueOf(err));
        }
        if (result instanceof BackendResult.Ok ok) {
            try {
                Outcome outcome = reader.read(r, ok.body());
                h.onSuccess();
                return outcome;
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
