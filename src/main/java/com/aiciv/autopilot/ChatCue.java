package com.aiciv.autopilot;

import java.util.Map;

/** A suggestion from the planner that the bot could say something (a phrase category and its placeholder values). */
public record ChatCue(String category, Map<String, String> vars, boolean priority) {}
