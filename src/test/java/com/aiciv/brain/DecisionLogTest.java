package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecisionLogTest {
    @TempDir Path dir;

    private static JsonObject record(int n) {
        JsonObject o = new JsonObject();
        o.addProperty("n", n);
        o.addProperty("prompt", "SECRET PROMPT");
        o.addProperty("snapshot", "big text");
        return o;
    }

    @Test
    void tailIsNewestFirstLimitedAndWithoutPrompts() {
        DecisionLog log = new DecisionLog(dir);
        for (int i = 1; i <= 5; i++) log.append("Ada", record(i));
        List<JsonObject> tail = log.tail("Ada", 3);
        assertEquals(List.of(5, 4, 3), tail.stream().map(o -> o.get("n").getAsInt()).toList());
        assertFalse(tail.get(0).has("prompt"));
        assertFalse(tail.get(0).has("snapshot"));
        assertEquals(5, log.tail("Ada", 50).size());
        assertTrue(log.tail("Nobody", 5).isEmpty());
    }

    @Test
    void lastReturnsACopyWithoutPromptsAndIgnoresCase() {
        DecisionLog log = new DecisionLog(dir);
        assertNull(log.last("Ada"));
        log.append("Ada", record(7));
        JsonObject last = log.last("ADA");
        assertEquals(7, last.get("n").getAsInt());
        assertFalse(last.has("prompt"));
        last.addProperty("n", 99);
        assertEquals(7, log.last("Ada").get("n").getAsInt());
    }

    @Test
    void damagedAndPartialLinesAreSkipped() throws Exception {
        Files.writeString(dir.resolve("Ada.jsonl"), "{\"n\": 1}\nnot json\n{\"n\": 3}\n{\"n\": ");
        assertEquals(List.of(3, 1), new DecisionLog(dir).tail("Ada", 10).stream().map(o -> o.get("n").getAsInt()).toList());
    }
}
