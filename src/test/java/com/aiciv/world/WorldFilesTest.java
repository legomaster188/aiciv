package com.aiciv.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFilesTest {
    @TempDir
    Path dir;

    @Test
    void anUnreadableFileIsSetAsideKeepingTheNewest() throws Exception {
        Path file = dir.resolve("placed.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        Path bad = WorldFiles.setAside(file);
        assertEquals(dir.resolve("placed.json.bad"), bad);
        assertFalse(Files.exists(file));
        assertEquals("{broken", Files.readString(bad, StandardCharsets.UTF_8));

        Files.writeString(file, "{newer", StandardCharsets.UTF_8);
        WorldFiles.setAside(file);
        assertEquals("{newer", Files.readString(bad, StandardCharsets.UTF_8));
    }

    @Test
    void setAsideQuietlyReportsWhereItWent() throws Exception {
        Path file = dir.resolve("structures.json");
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        assertTrue(WorldFiles.setAsideQuietly(file).startsWith("kept as "));
        assertTrue(WorldFiles.setAsideQuietly(file).startsWith("could not move it aside"));
    }
}
