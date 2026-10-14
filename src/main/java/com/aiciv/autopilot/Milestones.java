package com.aiciv.autopilot;

import java.util.ArrayList;
import java.util.List;

/** Progress worth bragging about. Pure. */
public final class Milestones {
    public static final List<String> ALL = List.of("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "iron_armor", "diamond",
            "home", "diamond_pickaxe", "nether", "ender_eye", "end");

    private Milestones() {}

    /** The milestones the situation shows, in {@link #ALL} order. */
    public static List<String> reached(Situation s) {
        List<String> out = new ArrayList<>();
        for (String m : ALL) if (reached(m, s)) out.add(m);
        return out;
    }

    private static boolean reached(String m, Situation s) {
        return switch (m) {
            case "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe" -> s.hasTool(m);
            case "iron_armor" -> s.wornTier("chestplate") >= 3;
            case "diamond" -> s.count("diamond") > 0;
            case "home" -> s.home().isPresent();
            case "nether" -> s.inNether();
            case "ender_eye" -> s.count("ender_eye") > 0;
            case "end" -> s.inEnd();
            default -> false;
        };
    }
}
