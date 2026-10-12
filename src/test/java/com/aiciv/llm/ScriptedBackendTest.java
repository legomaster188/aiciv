package com.aiciv.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptedBackendTest {
    @TempDir Path dir;

    @Test
    void repliesInOrderThenEmptyAndRecordsRequests() throws Exception {
        JsonObject m1 = JsonParser.parseString("{\"content\": \"one\"}").getAsJsonObject();
        ScriptedBackend b = new ScriptedBackend(List.of(m1));
        JsonObject req = new JsonObject();
        req.addProperty("model", "x");
        BackendResult.Ok first = (BackendResult.Ok) b.send(req).get();
        assertEquals("one", ReplyParser.parse(first.body()).plainText());
        BackendResult.Ok second = (BackendResult.Ok) b.send(req).get();
        assertNull(ReplyParser.parse(second.body()).plainText());
        assertEquals(2, b.requests().size());
    }

    @Test
    void loadsAFile() throws Exception {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "[{\"content\": \"hi\"}, {\"content\": \"bye\"}]");
        ScriptedBackend b = ScriptedBackend.fromFile(f);
        assertEquals("hi", ReplyParser.parse(((BackendResult.Ok) b.send(new JsonObject()).get()).body()).plainText());
    }
}
