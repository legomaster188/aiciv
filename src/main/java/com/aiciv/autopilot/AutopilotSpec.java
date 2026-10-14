package com.aiciv.autopilot;

/** How one autopilot model key resolves: which personality decides, who writes its chat lines, how often it re-thinks. */
public record AutopilotSpec(String modelKey, String personality, String chatModel, int thinkSeconds) {}
