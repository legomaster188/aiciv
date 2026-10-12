package com.aiciv.perception;

import java.util.List;

/** Everything a bot is told about its situation. Lists are sorted nearest first. */
public record SnapshotData(
        String name, int x, int y, int z,
        int health, int maxHealth, int food,
        long day, String timeOfDay, String biome,
        String holding, List<String> inventory,
        List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs,
        String currentAction, String lastResult) {}
