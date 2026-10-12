package com.aiciv.perception;

import java.util.List;

/**
 * Everything a bot is told about its situation. Lists are sorted nearest first.
 * {@code remembered} holds known blocks farther than the scan radius, already worded for the prompt.
 */
public record SnapshotData(
        String name, int x, int y, int z,
        int health, int maxHealth, int food,
        long day, String timeOfDay, String biome,
        String holding, String equipment, List<String> inventory,
        List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs,
        List<String> remembered,
        String currentAction, String lastResult) {}
