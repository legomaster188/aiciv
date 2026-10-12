package com.aiciv.brain;

import com.aiciv.AiCiv;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One JSON line per decision in logs/aiciv/<bot>.jsonl. */
public final class DecisionLog {
    private final Path dir;

    public DecisionLog(Path dir) {
        this.dir = dir;
    }

    public void append(String bot, JsonObject record) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(bot + ".jsonl"), record + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AiCiv.LOG.warn("Could not write the AiCiv decision log for {}", bot, e);
        }
    }

    public Path fileFor(String bot) {
        return dir.resolve(bot + ".jsonl");
    }
}
