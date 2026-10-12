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

    /** Writes a temp file beside {@code file} and moves it over, so a crash mid-write never leaves half a file. */
    public static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
