package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import java.util.LinkedHashMap;
import java.util.Map;

/** What crafting an item takes, in raw materials, and how much of that a bot already has. Pure. */
public final class Costs {
    private Costs() {}

    private static Map<String, Integer> of(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    /** Raw materials for one item (materials: log in log-equivalents, cobblestone, iron_ingot, diamond, ...); empty when unknown. */
    public static Map<String, Integer> raw(String item) {
        return switch (item) {
            case "wooden_pickaxe", "wooden_axe", "chest" -> of("log", 2);
            case "wooden_sword", "wooden_hoe", "wooden_shovel", "crafting_table" -> of("log", 1);
            case "stone_pickaxe", "stone_axe" -> of("cobblestone", 3, "log", 1);
            case "stone_sword", "stone_hoe" -> of("cobblestone", 2, "log", 1);
            case "iron_pickaxe", "iron_axe" -> of("iron_ingot", 3, "log", 1);
            case "iron_sword" -> of("iron_ingot", 2, "log", 1);
            case "iron_helmet" -> of("iron_ingot", 5);
            case "iron_chestplate" -> of("iron_ingot", 8);
            case "iron_leggings" -> of("iron_ingot", 7);
            case "iron_boots" -> of("iron_ingot", 4);
            case "diamond_pickaxe" -> of("diamond", 3, "log", 1);
            case "diamond_sword" -> of("diamond", 2, "log", 1);
            case "furnace" -> of("cobblestone", 8);
            case "shield" -> of("iron_ingot", 1, "log", 2);
            case "bucket" -> of("iron_ingot", 3);
            case "flint_and_steel" -> of("iron_ingot", 1, "flint", 1);
            case "white_bed" -> of("wool", 3, "log", 1);
            case "ender_eye" -> of("blaze_rod", 1, "ender_pearl", 1);
            case "bread" -> of("wheat", 3);
            default -> of();
        };
    }

    public static boolean needsTable(String item) {
        return switch (item) {
            case "crafting_table", "flint_and_steel", "ender_eye" -> false;
            default -> !raw(item).isEmpty();
        };
    }

    /** {@code raw × count}, plus one log when a crafting table is needed and none is near. Eyes: one rod per two, rounded up. */
    public static Map<String, Integer> forCraft(String item, int count, Situation s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : raw(item).entrySet()) {
            int per = e.getValue();
            out.put(e.getKey(), item.equals("ender_eye") && e.getKey().equals("blaze_rod") ? (count + 1) / 2 : per * count);
        }
        if (needsTable(item) && !s.tableNear()) out.merge("log", 1, Integer::sum);
        return out;
    }

    /** How much of a material the bot has. */
    public static int have(String material, Situation s) {
        return switch (material) {
            case "log" -> s.logEquivalents();
            case "cobblestone" -> s.count("cobblestone") + s.count("cobbled_deepslate");
            case "wool" -> s.inventory().entrySet().stream().filter(e -> e.getKey().endsWith("_wool")).mapToInt(Map.Entry::getValue).sum();
            default -> s.count(material);
        };
    }

    /** The materials still short, with how many are missing; order kept. */
    public static Map<String, Integer> missing(Map<String, Integer> need, Situation s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int shortfall = e.getValue() - have(e.getKey(), s);
            if (shortfall > 0) out.put(e.getKey(), shortfall);
        }
        return out;
    }
}
