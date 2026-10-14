package com.aiciv.autopilot;

import com.aiciv.actions.ActionFactory;
import com.aiciv.modules.ToolModules;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A fluent {@link Situation} builder for planner and goal tests: an empty overworld at 0 64 0, today's actions. */
public final class Sit {
    private String name = "Ada";
    private String dimension = "overworld";
    private int x, y = 64, z, health = 20, food = 20;
    private boolean night;
    private long tick = 1000;
    private final Map<String, Integer> inventory = new LinkedHashMap<>();
    private final Set<String> worn = new HashSet<>();
    private String holding;
    private final List<Sighting> blocks = new ArrayList<>();
    private final List<Sighting> mobs = new ArrayList<>();
    private final List<String> remembered = new ArrayList<>();
    private final Set<String> tags = new HashSet<>();
    private final Set<String> actions = new HashSet<>(ActionFactory.knownKeys());
    private final Set<String> flags = new HashSet<>();
    private Tuning tuning = Tuning.DEFAULT;

    public static Sit sit() {
        return new Sit();
    }

    public Sit inv(String id, int n) { inventory.merge(id, n, Integer::sum); return this; }
    public Sit wear(String id) { worn.add(id); return this; }
    public Sit hold(String id) { holding = id; return this; }
    public Sit block(String label, int distance) { blocks.add(new Sighting(label, 1, distance, "N")); return this; }
    public Sit mob(String label, int count, int distance, String direction) { mobs.add(new Sighting(label, count, distance, direction)); return this; }
    public Sit remember(String line) { remembered.add(line); return this; }
    public Sit tag(String t) { tags.add(t); return this; }
    public Sit flag(String f) { flags.add(f); return this; }
    public Sit dim(String d) { dimension = d; return this; }
    public Sit tick(long t) { tick = t; return this; }
    public Sit at(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
    public Sit food(int f) { food = f; return this; }
    public Sit tuning(Tuning t) { tuning = t; return this; }

    /** Replaces the action set. */
    public Sit actions(String... keys) {
        actions.clear();
        for (String k : keys) actions.add(ToolModules.normalise(k));
        return this;
    }

    /** Adds to the action set (names may be spelled with underscores). */
    public Sit withActions(String... keys) {
        for (String k : keys) actions.add(ToolModules.normalise(k));
        return this;
    }

    public Sit without(String key) {
        actions.remove(ToolModules.normalise(key));
        return this;
    }

    public Situation build() {
        return new Situation(name, dimension, x, y, z, health, food, night, tick, new LinkedHashMap<>(inventory), new HashSet<>(worn),
                holding, 30, List.copyOf(blocks), List.of(), List.copyOf(mobs), List.of(), List.copyOf(remembered),
                new HashSet<>(tags), List.of(), new HashSet<>(actions), null, false, new HashSet<>(flags), new HashMap<>(),
                List.of(), tuning, null);
    }
}
