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
}
