package com.aiciv.autopilot.personality;

import java.util.List;
import java.util.Map;

/** One personality: traits, goal weights by goal id, and chat phrases by category. */
public record Personality(String id, String name, String description, Traits traits,
                          Map<String, Double> goals, Map<String, List<String>> phrases) {}
