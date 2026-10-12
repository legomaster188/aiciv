package com.aiciv.brain;

import com.aiciv.AiCiv;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One JSON line per decision in logs/aiciv/<bot>.jsonl. */
public final class DecisionLog {
    /** Fields that would make a panel response large or carry prompt text. */
    private static final List<String> HEAVY_FIELDS = List.of("prompt", "system", "user", "snapshot", "messages");
    /** The tail read is bounded, so a huge log costs the same as a small one. */
    private static final int TAIL_BYTES = 512 * 1024;

    private final Path dir;
    /** The newest record per bot (lower-case name), for the admin panel. Written on the server thread, read anywhere. */
    private final Map<String, JsonObject> latest = new ConcurrentHashMap<>();

    public DecisionLog(Path dir) {
        this.dir = dir;
    }

    public void append(String bot, JsonObject record) {
        latest.put(bot.toLowerCase(Locale.ROOT), record.deepCopy());
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

    /** The newest decision recorded for the bot since the server started, or null. Returns a copy. */
    public JsonObject last(String bot) {
        JsonObject r = latest.get(bot.toLowerCase(Locale.ROOT));
        return r == null ? null : strip(r.deepCopy());
    }

    /** The last {@code limit} records of the bot's log, newest first, without prompts or snapshots. Reads the file tail. */
    public List<JsonObject> tail(String bot, int limit) {
        Path file = fileFor(bot);
        List<JsonObject> out = new ArrayList<>();
        if (limit <= 0 || !Files.isRegularFile(file)) return out;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long length = f.length();
            long start = Math.max(0, length - TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            f.seek(start);
            f.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            for (int i = lines.length - 1; i >= 0 && out.size() < limit; i--) {
                if (i == 0 && start > 0) break; // the first line of a mid-file read is cut off
                if (lines[i].isBlank()) continue;
                try {
                    out.add(strip(JsonParser.parseString(lines[i]).getAsJsonObject()));
                } catch (RuntimeException e) {
                    // a half-written or damaged line is skipped
                }
            }
        } catch (IOException e) {
            AiCiv.LOG.warn("Could not read the AiCiv decision log for {}", bot, e);
        }
        return out;
    }

    private static JsonObject strip(JsonObject record) {
        for (String field : HEAVY_FIELDS) record.remove(field);
        return record;
    }
}
