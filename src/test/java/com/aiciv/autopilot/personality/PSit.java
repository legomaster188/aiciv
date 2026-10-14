package com.aiciv.autopilot.personality;

import com.aiciv.autopilot.AutopilotMemory;
import com.aiciv.autopilot.Situation;
import com.aiciv.autopilot.Tuning;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small fluent Situation builder for the personality tests. */
final class PSit {
    String name = "Ada";
    int x, y = 64, z, health = 20, food = 20;
    boolean night;
    long tick = 1000;
    String pinned;
    final Map<String, Integer> inventory = new HashMap<>();
    final List<Sighting> mobs = new ArrayList<>();
    final List<Sighting> blocks = new ArrayList<>();
    final List<String> hostiles = new ArrayList<>();
    final List<String> structures = new ArrayList<>();
    final Set<String> actions = new HashSet<>(Set.of("goto", "explore", "sleep"));
    final Map<String, Long> lastDone = new HashMap<>();

    PSit inv(String id, int n) { inventory.merge(id, n, Integer::sum); return this; }
    PSit health(int h) { health = h; return this; }
    PSit food(int f) { food = f; return this; }
    PSit night() { night = true; return this; }
    PSit tick(long t) { tick = t; return this; }
    PSit pin(String goal) { pinned = goal; return this; }
    PSit hostile(String label, int distance, String dir) {
        mobs.add(new Sighting(label, 1, distance, dir));
        hostiles.add(label);
        return this;
    }
    PSit block(String label, int distance) { blocks.add(new Sighting(label, 1, distance, "N")); return this; }
    PSit homeAt(int hx, int hy, int hz) {
        structures.add(Situation.homeName(name) + " by Ada at " + hx + " " + hy + " " + hz);
        return this;
    }
    PSit actions(String... keys) { actions.clear(); actions.addAll(List.of(keys)); return this; }
    PSit done(String key, long at) { lastDone.put(key, at); return this; }

    Situation build() {
        return new Situation(name, "overworld", x, y, z, health, food, night, tick, inventory, Set.of(), null, 30,
                blocks, List.of(), mobs, hostiles, List.of(), Set.of(), structures, actions, null, false,
                Set.of(), lastDone, List.of(), Tuning.DEFAULT, pinned);
    }

    static AutopilotMemory mem() {
        return new AutopilotMemory();
    }
}
