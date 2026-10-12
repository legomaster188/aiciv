package com.aiciv.bot;

import com.aiciv.config.AiCivConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The bots that belong to this world, saved as <world>/aiciv/roster.json. */
public final class Roster {
    public static final class Entry {
        public String name;
        public String model;
        public String persona = "";
        public String goal = "";
        public boolean paused;

        public Entry() {}

        Entry(String name) {
            this.name = name;
        }
    }

    private final List<Entry> entries;

    private Roster(List<Entry> entries) {
        this.entries = entries;
    }

    public static Roster empty() {
        return new Roster(new ArrayList<>());
    }

    public static Roster load(Path file) throws IOException {
        if (Files.notExists(file)) return empty();
        Entry[] arr = AiCivConfig.GSON.fromJson(Files.readString(file), Entry[].class);
        List<Entry> list = new ArrayList<>();
        if (arr != null) {
            for (Entry e : arr) {
                if (e == null || e.name == null) continue;
                if (e.persona == null) e.persona = "";
                if (e.goal == null) e.goal = "";
                list.add(e);
            }
        }
        return new Roster(list);
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, AiCivConfig.GSON.toJson(entries));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<Entry> get(String name) {
        return entries.stream().filter(e -> e.name.equalsIgnoreCase(name)).findFirst();
    }

    public Entry getOrAdd(String name) {
        return get(name).orElseGet(() -> {
            Entry e = new Entry(name);
            entries.add(e);
            return e;
        });
    }

    public boolean remove(String name) {
        return entries.removeIf(e -> e.name.equalsIgnoreCase(name));
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }
}
