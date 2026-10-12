package com.aiciv.brain;

import java.util.List;

public final class PromptBuilder {
    private PromptBuilder() {}

    public static String system(String name, String persona, String goal, List<String> others, int maxActions, String toolGuide) {
        String people = others.isEmpty() ? "nobody else yet" : String.join(", ", others);
        String who = persona == null || persona.isBlank() ? "friendly and curious" : persona;
        String aim = goal == null || goal.isBlank() ? "help the settlement survive and grow" : goal;
        return """
                You are %s, a player in a Minecraft survival world. You are one of %d people founding a settlement here. \
                The others are: %s.
                Your personality: %s.
                Your long-term goal: %s.

                How to play:
                - You play survival like a normal player. You cannot use commands or cheats.
                - You act by calling the provided functions. Each turn you may call up to %d; they run in order.
                - Saying something does not interrupt what you are doing. Any other action replaces your current one.
                - Chat like a real player: short messages, in character.
                - Coordinates: x grows to the east, z grows to the south, y is height.
                - You only know what your situation lists. Explore to find more.
                %s""".formatted(name, others.size() + 1, people, who, aim, maxActions, toolGuide);
    }

    /** Instructions for a menu-mode decision: who the bot is and that it must pick exactly one listed option. */
    public static String menuSystem(String name, String persona, String goal, List<String> others) {
        String people = others.isEmpty() ? "nobody else yet" : String.join(", ", others);
        String who = persona == null || persona.isBlank() ? "friendly and curious" : persona;
        String aim = goal == null || goal.isBlank() ? "help the settlement survive and grow" : goal;
        return ("You are %s, a player in a Minecraft survival world, founding a settlement with: %s. "
                + "Personality: %s. Long-term goal: %s. You play like a normal survival player. "
                + "Pick the single best next action for %s from the listed options.").formatted(name, people, who, aim, name);
    }

    public static String menuUser(List<String> events, String snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(events.isEmpty() ? "Recent events: none\n" : "Recent events (oldest first):\n" + bullets(events));
        sb.append('\n');
        sb.append("Your situation now:\n").append(snapshot);
        return sb.toString();
    }

    public static String user(List<String> notes, List<String> events, String snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(notes.isEmpty() ? "Your notes: none\n" : "Your notes:\n" + bullets(notes));
        sb.append('\n');
        sb.append(events.isEmpty() ? "Recent events: none\n" : "Recent events (oldest first):\n" + bullets(events));
        sb.append('\n');
        sb.append("Your situation now:\n").append(snapshot).append("\n\n");
        sb.append("Decide what to do next.");
        return sb.toString();
    }

    private static String bullets(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append("- ").append(l).append('\n');
        return sb.toString();
    }
}
