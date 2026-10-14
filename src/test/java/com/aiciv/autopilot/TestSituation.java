package com.aiciv.autopilot;

import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small fluent builder for Task 1's tests; everything defaults to an empty overworld situation at 0 64 0. */
final class TestSituation {
    String name = "Ada";
    String dimension = "overworld";
    int x, y = 64, z, health = 20, food = 20;
    boolean night, busy;
    long tick = 1000;
    final Map<String, Integer> inventory = new LinkedHashMap<>();
    final Set<String> worn = new HashSet<>();
    String holding;
    final List<Sighting> blocks = new ArrayList<>();
    final List<Sighting> players = new ArrayList<>();
    final List<Sighting> mobs = new ArrayList<>();
    final List<String> hostiles = new ArrayList<>();
    final List<String> remembered = new ArrayList<>();
    final Set<String> tags = new HashSet<>();
    final List<String> structures = new ArrayList<>();
    final Set<String> actions = new HashSet<>(Set.of("goto", "mine", "craft", "smelt", "explore", "attack", "collect"));
    String currentAction;
    final Set<String> flags = new HashSet<>();
    final Map<String, Long> lastDone = new HashMap<>();

    TestSituation inv(String id, int n) { inventory.merge(id, n, Integer::sum); return this; }
    TestSituation wear(String id) { worn.add(id); return this; }
    TestSituation hold(String id) { holding = id; return this; }
    TestSituation block(String label, int distance) { blocks.add(new Sighting(label, 1, distance, "N")); return this; }
    TestSituation player(String label, int distance) { players.add(new Sighting(label, 1, distance, "N")); return this; }
    TestSituation hostile(String label, int distance) { mobs.add(new Sighting(label, 1, distance, "N")); hostiles.add(label); return this; }
    TestSituation remember(String line) { remembered.add(line); return this; }
    TestSituation structure(String line) { structures.add(line); return this; }
    TestSituation actions(String... keys) { actions.clear(); actions.addAll(List.of(keys)); return this; }
    TestSituation busy(boolean b) { busy = b; return this; }
    TestSituation tick(long t) { tick = t; return this; }

    Situation build() {
        return new Situation(name, dimension, x, y, z, health, food, night, tick, inventory, worn, holding, 30,
                blocks, players, mobs, hostiles, remembered, tags, structures, actions, currentAction, busy, flags, lastDone,
                List.of(), Tuning.DEFAULT, null);
    }
}
