package com.aiciv.autopilot;

import java.util.Map;

/** What the panel shows about an autopilot. */
public record AutopilotView(String personality, String personalityName, String goal, String step, String thought,
                            Map<String, Double> traits, String chatModel) {}
