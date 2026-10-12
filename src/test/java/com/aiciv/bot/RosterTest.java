package com.aiciv.bot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RosterTest {
    @TempDir Path dir;

    @Test
    void roundTripsAndIgnoresCase() throws Exception {
        Path file = dir.resolve("aiciv").resolve("roster.json");
        Roster r = Roster.load(file);
        assertTrue(r.all().isEmpty());
        Roster.Entry e = r.getOrAdd("Ada");
        e.model = "gemini-flash";
        e.persona = "cheerful";
        e.paused = true;
        r.save(file);
        Roster back = Roster.load(file);
        Roster.Entry ada = back.get("ADA").orElseThrow();
        assertEquals("Ada", ada.name);
        assertEquals("gemini-flash", ada.model);
        assertTrue(ada.paused);
        assertEquals("", ada.goal);
        assertSame(back.getOrAdd("ada"), ada);
        assertTrue(back.remove("ada"));
        assertTrue(back.all().isEmpty());
        assertFalse(Files.exists(dir.resolve("aiciv").resolve("roster.json.tmp")));
    }
}
