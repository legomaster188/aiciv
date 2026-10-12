package com.aiciv.perception;

/** Something seen nearby: count of that kind, distance in blocks to the nearest, and its direction. */
public record Sighting(String label, int count, int distance, String direction) {}
