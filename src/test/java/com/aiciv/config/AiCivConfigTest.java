package com.aiciv.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiCivConfigTest {
    @TempDir Path dir;

    @Test
    void writesDefaultsWhenMissingAndTheyAreValid() throws Exception {
        Path file = dir.resolve("aiciv").resolve("aiciv.json");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertTrue(Files.exists(file));
        assertEquals("gemini-flash", c.defaultModel);
        assertEquals("gemini", c.models.get("gemini-flash").provider);
        assertEquals("groq-llama", c.models.get("gemini-flash").fallback);
        assertEquals(4096, c.models.get("gemini-flash").maxTokens);
        assertEquals(1000, c.models.get("groq-llama").maxTokens);
        assertNull(c.providers.get("ollama").key);
        assertEquals(java.util.List.of(), c.validate());
        assertEquals(c.defaultModel, AiCivConfig.loadOrCreate(file).defaultModel);
    }

    @Test
    void defaultsHaveSixActionsAndThirtySecondTimeouts() throws Exception {
        AiCivConfig c = AiCivConfig.defaults();
        assertEquals(6, c.maxActionsPerDecision);
        c.providers.values().forEach(p -> assertEquals(30, p.timeoutSeconds));
    }

    @Test
    void toolCatalogDefaultsToGatedAndUnknownValuesAreReported() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x"}},
             "models": {"m": {"provider": "p", "model": "x"}, "n": {"provider": "p", "model": "y", "toolCatalog": "tiny"}},
             "defaultModel": "m"}""");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertEquals("gated", c.models.get("m").toolCatalog);
        assertEquals(List.of("model 'n' has unknown toolCatalog 'tiny' (use gated, full or compact)"), c.validate());
        assertEquals("gated", AiCivConfig.defaults().models.get("groq-llama").toolCatalog);
    }

    @Test
    void timeoutSecondsLoadsFromFile() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x", "timeoutSeconds": 60}},
             "models": {"m": {"provider": "p", "model": "x"}}, "defaultModel": "m"}""");
        assertEquals(60, AiCivConfig.loadOrCreate(file).providers.get("p").timeoutSeconds);
    }

    @Test
    void missingFieldsGetDefaults() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x"}},
             "models": {"m": {"provider": "p", "model": "x"}},
             "defaultModel": "m", "idleWakeSeconds": 30}""");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertEquals(30, c.idleWakeSeconds);
        assertEquals(15, c.mentionCooldownSeconds);
        assertEquals(64, c.hearingRange);
        assertEquals("openai", c.providers.get("p").type);
        assertEquals(2, c.providers.get("p").maxInFlight);
        assertEquals(1000, c.models.get("m").maxTokens);
        assertNotNull(c.models.get("m").extra);
        assertEquals(java.util.List.of(), c.validate());
    }

    @Test
    void validateReportsBrokenReferences() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x", "resetZone": "Not/AZone"}},
             "models": {"m": {"provider": "nope", "model": "x", "fallback": "ghost"}},
             "defaultModel": "missing"}""");
        String problems = String.join("\n", AiCivConfig.loadOrCreate(file).validate());
        assertTrue(problems.contains("unknown provider 'nope'"), problems);
        assertTrue(problems.contains("unknown model 'ghost'"), problems);
        assertTrue(problems.contains("invalid resetZone"), problems);
        assertTrue(problems.contains("defaultModel 'missing'"), problems);
    }

    @Test
    void validateRequiresAnHttpOrHttpsBaseUrl() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"q": {"baseUrl": "localhost:11434/v1"}},
             "models": {"m": {"provider": "q", "model": "x"}},
             "defaultModel": "m"}""");
        String problems = String.join("\n", AiCivConfig.loadOrCreate(file).validate());
        assertTrue(problems.contains("provider 'q' baseUrl must start with http:// or https://"), problems);
    }

    @Test
    void typesafeProvidersAreValidAndForceMenuMode() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"jev-or": {"type": "typesafe", "baseUrl": "https://openrouter.ai", "decisionsPath": "/api/alpha/decisions", "key": "openrouter"},
                           "chat": {"baseUrl": "http://localhost:1/v1"}},
             "models": {"jev": {"provider": "jev-or", "model": "typesafe/jev-1.13"},
                        "menu-chat": {"provider": "chat", "model": "x", "decisionMode": "menu"},
                        "tools-chat": {"provider": "chat", "model": "x"}},
             "defaultModel": "tools-chat"}""");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertEquals(List.of(), c.validate());
        assertEquals("/api/alpha/decisions", c.providers.get("jev-or").decisionsPath);
        assertEquals("/v1/systemone", c.providers.get("chat").decisionsPath);
        assertTrue(c.isMenuMode("jev"));
        assertTrue(c.isMenuMode("menu-chat"));
        assertFalse(c.isMenuMode("tools-chat"));
        assertFalse(c.isMenuMode("unknown"));
    }

    @Test
    void validateRejectsAnUnknownDecisionMode() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"q": {"baseUrl": "http://localhost:1/v1"}},
             "models": {"m": {"provider": "q", "model": "x", "decisionMode": "psychic"}},
             "defaultModel": "m"}""");
        String problems = String.join("\n", AiCivConfig.loadOrCreate(file).validate());
        assertTrue(problems.contains("unknown decisionMode 'psychic'"), problems);
    }
    @Test
    void validateRejectsAToolsModelWhoseFallbackIsTypesafe() throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"jev": {"type": "typesafe", "baseUrl": "https://openrouter.ai"},
                           "chat": {"baseUrl": "http://localhost:1/v1"}},
             "models": {"jev": {"provider": "jev", "model": "typesafe/jev-1.13"},
                        "tools-chat": {"provider": "chat", "model": "x", "fallback": "relay"},
                        "relay": {"provider": "chat", "model": "y", "decisionMode": "menu", "fallback": "jev"},
                        "menu-chat": {"provider": "chat", "model": "x", "decisionMode": "menu", "fallback": "jev"}},
             "defaultModel": "menu-chat"}""");
        List<String> problems = AiCivConfig.loadOrCreate(file).validate();
        assertEquals(List.of("model 'tools-chat' decides with tools, but its fallback 'jev' uses typesafe provider 'jev',"
                + " which only picks from menus"), problems);
    }

    @Test
    void panelIsOnAtPort8765WhenTheBlockIsMissingOrNull() throws Exception {
        assertTrue(AiCivConfig.defaults().panel.enabled);
        assertEquals(8765, AiCivConfig.defaults().panel.port);
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, "{\"defaultModel\": \"x\"}");
        assertEquals(8765, AiCivConfig.loadOrCreate(file).panel.port);
        Files.writeString(file, "{\"panel\": null}");
        assertTrue(AiCivConfig.loadOrCreate(file).panel.enabled);
        Files.writeString(file, "{\"panel\": {\"enabled\": false, \"port\": 9000}}");
        AiCivConfig c = AiCivConfig.loadOrCreate(file);
        assertFalse(c.panel.enabled);
        assertEquals(9000, c.panel.port);
    }

    @Test
    void validateRejectsABadPanelPortOnlyWhenEnabled() throws Exception {
        AiCivConfig c = AiCivConfig.defaults();
        c.panel.port = 70000;
        assertEquals(List.of("panel.port 70000 is not a valid port (1-65535)"), c.validate());
        c.panel.enabled = false;
        assertEquals(List.of(), c.validate());
    }

    private AiCivConfig autopilotConfig(String models) throws Exception {
        Path file = dir.resolve("aiciv.json");
        Files.writeString(file, """
            {"providers": {"p": {"baseUrl": "http://x"}},
             "models": {"m": {"provider": "p", "model": "x"}, %s},
             "defaultModel": "m"}""".formatted(models));
        return AiCivConfig.loadOrCreate(file);
    }

    @Test
    void autopilotDefaultsAndTypeAreFilledIn() throws Exception {
        AiCivConfig c = autopilotConfig("\"a\": {\"type\": \"autopilot\", \"personality\": \"miner\"}");
        assertEquals("llm", c.models.get("m").type);
        assertTrue(c.models.get("a").isAutopilot());
        assertEquals(5, c.autopilot.thinkSeconds);
        assertEquals(500, c.autopilot.gapMillis);
        assertEquals(20, c.autopilot.checkTicks);
        assertEquals(4, c.autopilot.maxDecisionsPerTick);
        assertEquals(30, c.autopilot.chatCooldownSeconds);
        assertEquals(60, c.autopilot.chatLlmCooldownSeconds);
        assertEquals(2000, c.autopilot.globalChatGapMillis);
        assertEquals(20, c.autopilot.chatLlmMaxAgeSeconds);
    }

    @Test
    void anAutopilotNeedsNoProviderAndIsNotMenuMode() throws Exception {
        AiCivConfig c = autopilotConfig("\"a\": {\"type\": \"autopilot\", \"personality\": \"miner\"}");
        assertEquals(List.of(), c.validate(java.util.Set.of("miner")));
        assertEquals(List.of(), c.validate());
        assertFalse(c.isMenuMode("a"));
    }

    @Test
    void autopilotValidationMessages() throws Exception {
        AiCivConfig c = autopilotConfig("""
            "t": {"type": "robot"},
            "none": {"type": "autopilot"},
            "lost": {"type": "autopilot", "personality": "pirate"},
            "chat1": {"type": "autopilot", "personality": "miner", "chatModel": "nope"},
            "chat2": {"type": "autopilot", "personality": "miner", "chatModel": "none"},
            "fb": {"type": "autopilot", "personality": "miner", "fallback": "m"}""");
        c.autopilot.thinkSeconds = 0;
        c.autopilot.checkTicks = 0;
        c.autopilot.maxDecisionsPerTick = 0;
        List<String> problems = c.validate(java.util.Set.of("miner", "guard", "builder"));
        assertTrue(problems.contains("model 't' has unknown type 'robot' (use llm or autopilot)"), problems.toString());
        assertTrue(problems.contains("autopilot model 'none' has no personality"), problems.toString());
        assertTrue(problems.contains("autopilot model 'lost' uses unknown personality 'pirate' (known: builder, guard, miner)"), problems.toString());
        assertTrue(problems.contains("autopilot model 'chat1' uses unknown chatModel 'nope'"), problems.toString());
        assertTrue(problems.contains("autopilot model 'chat2' chats with 'none', which is an autopilot; chatModel must be an LLM model"), problems.toString());
        assertTrue(problems.contains("autopilot model 'fb' cannot have a fallback"), problems.toString());
        assertTrue(problems.contains("autopilot.thinkSeconds must be at least 1"), problems.toString());
        assertTrue(problems.contains("autopilot.checkTicks must be at least 1"), problems.toString());
        assertTrue(problems.contains("autopilot.maxDecisionsPerTick must be at least 1"), problems.toString());
        assertTrue(problems.stream().noneMatch(p -> p.contains("provider")), problems.toString());
        assertTrue(c.validate().stream().noneMatch(p -> p.contains("unknown personality")), "null skips personality checks");
    }
}
