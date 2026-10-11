package com.aiciv.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyStoreTest {
    @TempDir Path dir;

    @Test
    void createsAnEmptyFile() throws Exception {
        Path file = dir.resolve("aiciv").resolve("keys.json");
        KeyStore keys = KeyStore.load(file, name -> null);
        assertTrue(Files.exists(file));
        assertEquals(Optional.empty(), keys.get("gemini"));
    }

    @Test
    void environmentOverridesFileAndBlankIsIgnored() throws Exception {
        Path file = dir.resolve("keys.json");
        Files.writeString(file, "{\"gemini\": \"from-file\", \"groq\": \"  \", \"open-code\": \"oc\"}");
        Map<String, String> env = Map.of("AICIV_KEY_GEMINI", "from-env");
        KeyStore keys = KeyStore.load(file, env::get);
        assertEquals(Optional.of("from-env"), keys.get("gemini"));
        assertEquals(Optional.empty(), keys.get("groq"));
        assertEquals(Optional.of("oc"), keys.get("open-code"));
    }

    @Test
    void envNameIsUpperCaseWithUnderscores() {
        assertEquals("AICIV_KEY_OPEN_CODE", KeyStore.envName("open-code"));
    }
}
