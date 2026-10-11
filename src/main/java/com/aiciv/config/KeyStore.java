package com.aiciv.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** API keys from keys.json, overridable by AICIV_KEY_<NAME> environment variables. Never logs values. */
public final class KeyStore {
    private final Map<String, String> fileKeys;
    private final Function<String, String> env;

    private KeyStore(Map<String, String> fileKeys, Function<String, String> env) {
        this.fileKeys = fileKeys;
        this.env = env;
    }

    /** Loads keys.json, creating an empty one when it is missing. */
    public static KeyStore load(Path file, Function<String, String> env) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{}\n");
        }
        Map<String, String> keys = new HashMap<>();
        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (root != null && root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) keys.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new KeyStore(keys, env);
    }

    public static String envName(String keyName) {
        return "AICIV_KEY_" + keyName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    public Optional<String> get(String keyName) {
        String fromEnv = env.apply(envName(keyName));
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv.trim());
        String fromFile = fileKeys.get(keyName);
        if (fromFile != null && !fromFile.isBlank()) return Optional.of(fromFile.trim());
        return Optional.empty();
    }
}
