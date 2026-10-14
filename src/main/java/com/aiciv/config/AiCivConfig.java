package com.aiciv.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** aiciv.json. Public fields so Gson can fill them; field initializers are the defaults. */
public final class AiCivConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    public Map<String, ModelConfig> models = new LinkedHashMap<>();
    public String defaultModel = "gemini-flash";
    public int idleWakeSeconds = 60;
    public int mentionCooldownSeconds = 15;
    /** Least time between a decision and the next one woken by actions ending; 0 turns it off. */
    public int decisionGapSeconds = 3;
    public int hearingRange = 64;
    public int maxNotes = 20;
    public int eventWindow = 10;
    public int maxActionsPerDecision = 6;
    public PanelConfig panel = new PanelConfig();
    public AutopilotConfig autopilot = new AutopilotConfig();

    /** The local web admin panel. Needs a restart to change. */
    public static final class PanelConfig {
        public boolean enabled = true;
        public int port = 8765;
    }

    /** Bots driven by the built-in planner instead of an LLM (model type "autopilot"). */
    public static final class AutopilotConfig {
        /** Idle re-think while not busy. */
        public int thinkSeconds = 5;
        /** Least time after an action ends before the next decision. */
        public int gapMillis = 500;
        /** Pulse period: how often each autopilot checks for danger, hunger and nightfall. */
        public int checkTicks = 20;
        public int maxDecisionsPerTick = 4;
        public int chatCooldownSeconds = 30;
        public int chatLlmCooldownSeconds = 60;
        public int globalChatGapMillis = 2000;
        public int chatLlmMaxAgeSeconds = 20;
    }

    public static final class ProviderConfig {
        /** "openai" for any OpenAI-compatible HTTP API, "typesafe" for a TypeSafe (Jev) decision API, "scripted" for canned replies. */
        public String type = "openai";
        public String baseUrl = "";
        /** Name of the key in keys.json, or null when the provider needs none. */
        public String key;
        /** Requests per minute, 0 for unlimited. */
        public int rpm;
        /** Requests per day, 0 for unlimited. */
        public int rpd;
        public String resetZone = "UTC";
        public int maxInFlight = 2;
        /** HTTP request timeout; raise for slow models. Values under 5 are treated as 5. */
        public int timeoutSeconds = 30;
        /** Typesafe providers only: where decisions are posted under baseUrl ("/api/alpha/decisions" on OpenRouter). */
        public String decisionsPath = "/v1/systemone";
        /** Scripted providers only: a JSON file next to aiciv.json. */
        public String script;

        public ProviderConfig() {}

        ProviderConfig(String baseUrl, String key, int rpm, int rpd, String resetZone, int maxInFlight) {
            this.baseUrl = baseUrl;
            this.key = key;
            this.rpm = rpm;
            this.rpd = rpd;
            this.resetZone = resetZone;
            this.maxInFlight = maxInFlight;
        }
    }

    public static final class ModelConfig {
        public String provider;
        public String model;
        public String fallback;
        public int maxTokens = 1000;
        /** "tools" (the model calls action functions) or "menu" (it picks one option from a short list). Typesafe providers always use menu. */
        public String decisionMode = "tools";
        /** Extra request fields merged into the body, for example {"reasoning_effort": "none"}. */
        public JsonObject extra = new JsonObject();
        /** "llm" (an LLM decides) or "autopilot" (the built-in planner decides). */
        public String type = "llm";
        /** Autopilot only: the personality id. */
        public String personality;
        /** Autopilot only: an LLM model key that writes chat lines. */
        public String chatModel;
        /** Autopilot only: overrides autopilot.thinkSeconds. */
        public Integer thinkSeconds;

        public ModelConfig() {}

        public boolean isAutopilot() {
            return "autopilot".equals(type);
        }

        ModelConfig(String provider, String model, String fallback) {
            this.provider = provider;
            this.model = model;
            this.fallback = fallback;
        }
    }

    /** Illustrative limits; the user replaces them with their real tier limits. */
    public static AiCivConfig defaults() {
        AiCivConfig c = new AiCivConfig();
        c.providers.put("gemini", new ProviderConfig("https://generativelanguage.googleapis.com/v1beta/openai", "gemini", 10, 250, "America/Los_Angeles", 2));
        c.providers.put("groq", new ProviderConfig("https://api.groq.com/openai/v1", "groq", 30, 1000, "UTC", 2));
        c.providers.put("opencode", new ProviderConfig("https://opencode.ai/zen/v1", "opencode", 10, 500, "UTC", 2));
        c.providers.put("ollama", new ProviderConfig("http://localhost:11434/v1", null, 0, 0, "UTC", 1));
        ModelConfig gemini = new ModelConfig("gemini", "gemini-2.5-flash", "groq-llama");
        gemini.maxTokens = 4096; // thinking tokens count against max_tokens; free tiers count requests, not tokens
        c.models.put("gemini-flash", gemini);
        c.models.put("groq-llama", new ModelConfig("groq", "llama-3.3-70b-versatile", null));
        c.models.put("opencode-free", new ModelConfig("opencode", "big-pickle", null));
        c.models.put("ollama-local", new ModelConfig("ollama", "llama3.2", null));
        return c;
    }

    /** Reads the file, or writes and returns the defaults when it does not exist. */
    public static AiCivConfig loadOrCreate(Path file) throws IOException {
        if (Files.notExists(file)) {
            AiCivConfig c = defaults();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(c));
            return c;
        }
        AiCivConfig c = GSON.fromJson(Files.readString(file), AiCivConfig.class);
        if (c == null) throw new IOException(file + " is empty");
        if (c.providers == null) c.providers = new LinkedHashMap<>();
        if (c.models == null) c.models = new LinkedHashMap<>();
        if (c.panel == null) c.panel = new PanelConfig();
        if (c.autopilot == null) c.autopilot = new AutopilotConfig();
        for (ModelConfig m : c.models.values()) {
            if (m.extra == null) m.extra = new JsonObject();
            if (m.decisionMode == null) m.decisionMode = "tools";
            if (m.type == null) m.type = "llm";
        }
        return c;
    }

    /** Human-readable problems; empty when the config is usable. Personality names are not checked. */
    public List<String> validate() {
        return validate(null);
    }

    /** As {@link #validate()}, also checking autopilot personalities against {@code personalityIds} unless it is null. */
    public List<String> validate(Set<String> personalityIds) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
            ModelConfig m = e.getValue();
            if (m.type != null && !"llm".equals(m.type) && !m.isAutopilot()) {
                problems.add("model '" + e.getKey() + "' has unknown type '" + m.type + "' (use llm or autopilot)");
                continue;
            }
            if (m.isAutopilot()) {
                validateAutopilot(e.getKey(), m, personalityIds, problems);
                continue;
            }
            if (m.provider == null || !providers.containsKey(m.provider)) {
                problems.add("model '" + e.getKey() + "' uses unknown provider '" + m.provider + "'");
            }
            if (m.model == null || m.model.isBlank()) {
                problems.add("model '" + e.getKey() + "' has no model id");
            }
            if (!"tools".equals(m.decisionMode) && !"menu".equals(m.decisionMode)) {
                problems.add("model '" + e.getKey() + "' has unknown decisionMode '" + m.decisionMode + "' (use tools or menu)");
            }
            if (m.fallback != null && !models.containsKey(m.fallback)) {
                problems.add("model '" + e.getKey() + "' falls back to unknown model '" + m.fallback + "'");
            }
        }
        for (Map.Entry<String, ProviderConfig> e : providers.entrySet()) {
            ProviderConfig p = e.getValue();
            if ("scripted".equals(p.type)) {
                if (p.script == null || p.script.isBlank()) problems.add("scripted provider '" + e.getKey() + "' has no script file");
            } else if (!"openai".equals(p.type) && !"typesafe".equals(p.type)) {
                problems.add("provider '" + e.getKey() + "' has unknown type '" + p.type + "'");
            } else if (p.baseUrl == null || p.baseUrl.isBlank()) {
                problems.add("provider '" + e.getKey() + "' has no baseUrl");
            } else if (!isHttpUrl(p.baseUrl)) {
                problems.add("provider '" + e.getKey() + "' baseUrl must start with http:// or https://");
            }
            try {
                ZoneId.of(p.resetZone);
            } catch (RuntimeException ex) {
                problems.add("provider '" + e.getKey() + "' has invalid resetZone '" + p.resetZone + "'");
            }
        }
        for (String key : models.keySet()) {
            if (models.get(key).isAutopilot()) continue;
            String typesafe = typesafeFallback(key);
            if (typesafe != null) {
                problems.add("model '" + key + "' decides with tools, but its fallback '" + typesafe + "' uses typesafe provider '"
                        + models.get(typesafe).provider + "', which only picks from menus");
            }
        }
        if (panel != null && panel.enabled && (panel.port < 1 || panel.port > 65535)) {
            problems.add("panel.port " + panel.port + " is not a valid port (1-65535)");
        }
        if (!models.containsKey(defaultModel)) {
            problems.add("defaultModel '" + defaultModel + "' is not a configured model");
        }
        if (autopilot != null) {
            if (autopilot.thinkSeconds < 1) problems.add("autopilot.thinkSeconds must be at least 1");
            if (autopilot.checkTicks < 1) problems.add("autopilot.checkTicks must be at least 1");
            if (autopilot.maxDecisionsPerTick < 1) problems.add("autopilot.maxDecisionsPerTick must be at least 1");
        }
        return problems;
    }

    private void validateAutopilot(String key, ModelConfig m, Set<String> personalityIds, List<String> problems) {
        if (m.personality == null || m.personality.isBlank()) {
            problems.add("autopilot model '" + key + "' has no personality");
        } else if (personalityIds != null && !personalityIds.contains(m.personality)) {
            problems.add("autopilot model '" + key + "' uses unknown personality '" + m.personality + "' (known: "
                    + String.join(", ", new TreeSet<>(personalityIds)) + ")");
        }
        if (m.chatModel != null) {
            ModelConfig chat = models.get(m.chatModel);
            if (chat == null) {
                problems.add("autopilot model '" + key + "' uses unknown chatModel '" + m.chatModel + "'");
            } else if (chat.isAutopilot()) {
                problems.add("autopilot model '" + key + "' chats with '" + m.chatModel
                        + "', which is an autopilot; chatModel must be an LLM model");
            }
        }
        if (m.fallback != null) problems.add("autopilot model '" + key + "' cannot have a fallback");
    }

    /** True when the model picks from an option menu: configured so, or served by a typesafe (decision-only) provider. */
    public boolean isMenuMode(String modelKey) {
        ModelConfig m = models.get(modelKey);
        if (m == null || m.isAutopilot()) return false;
        ProviderConfig p = providers.get(m.provider);
        return "menu".equals(m.decisionMode) || (p != null && "typesafe".equals(p.type));
    }

    /**
     * For a tools-mode model, the first model down its fallback chain served by a typesafe provider, or null. Such a
     * fallback could never take the model's requests, which are chat requests with tools.
     */
    private String typesafeFallback(String key) {
        if (isMenuMode(key)) return null;
        Set<String> seen = new HashSet<>();
        seen.add(key);
        String next = models.get(key).fallback;
        while (next != null && seen.add(next) && models.containsKey(next)) {
            ProviderConfig p = providers.get(models.get(next).provider);
            if (p != null && "typesafe".equals(p.type)) return next;
            next = models.get(next).fallback;
        }
        return null;
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
