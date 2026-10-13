package com.aiciv.building;

import com.aiciv.AiCiv;
import com.aiciv.world.WorldFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;

/** The running server's {@link StructureStore}, saved as {@code <world>/aiciv/structures.json}. Server thread only. */
public final class StructureRegistry {
    private static StructureRegistry current;

    private final MinecraftServer server;
    private final Path file;
    private final StructureStore store;

    private StructureRegistry(MinecraftServer server, Path file, StructureStore store) {
        this.server = server;
        this.file = file;
        this.store = store;
    }

    public static synchronized StructureRegistry of(MinecraftServer server) {
        if (current == null || current.server != server) current = load(server);
        return current;
    }

    private static StructureRegistry load(MinecraftServer server) {
        Path file = WorldFiles.file(server, "structures.json");
        StructureStore store = new StructureStore();
        if (Files.exists(file)) {
            try {
                store = StructureStore.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                AiCiv.LOG.warn("AiCiv could not read {}; starting with no structures ({})", file, WorldFiles.setAsideQuietly(file), e);
            }
        }
        return new StructureRegistry(server, file, store);
    }

    public StructureStore store() {
        return store;
    }

    /** Writes structures.json if anything changed since the last save. */
    public void save() {
        if (!store.dirty()) return;
        try {
            WorldFiles.writeAtomically(file, store.toJson());
            store.markClean();
        } catch (IOException e) {
            AiCiv.LOG.warn("AiCiv could not save {}", file, e);
        }
    }

    public static synchronized void unload(MinecraftServer server) {
        if (current == null || current.server != server) return;
        current.save();
        current = null;
    }
}
