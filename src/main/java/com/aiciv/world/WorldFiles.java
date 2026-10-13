package com.aiciv.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Files AiCiv keeps with a world, under {@code <world>/aiciv/}. */
public final class WorldFiles {
    private WorldFiles() {}

    public static Path file(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.ROOT).resolve("aiciv").resolve(name);
    }

    /**
     * Moves an unreadable {@code file} aside to {@code <name>.bad} (replacing an older one, so the newest is kept) and
     * returns where it went, so starting empty never overwrites what may still be recovered by hand.
     */
    public static Path setAside(Path file) throws IOException {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        return bad;
    }

    /** {@link #setAside}, for a log line: where the file went, or why it could not be moved. */
    public static String setAsideQuietly(Path file) {
        try {
            return "kept as " + setAside(file);
        } catch (IOException e) {
            return "could not move it aside: " + e;
        }
    }

    /** Writes a temp file beside {@code file} and moves it over, so a crash mid-write never leaves half a file. */
    public static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
