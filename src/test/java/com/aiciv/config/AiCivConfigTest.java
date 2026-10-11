package com.aiciv.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertNull(c.providers.get("ollama").key);
        assertEquals(java.util.List.of(), c.validate());
        assertEquals(c.defaultModel, AiCivConfig.loadOrCreate(file).defaultModel);
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
}
