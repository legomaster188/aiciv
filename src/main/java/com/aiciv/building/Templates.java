package com.aiciv.building;

import com.aiciv.world.BlockNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ready-made blueprints for small models: {@code m} is the material, row 0 faces the builder. */
public final class Templates {
    public static final List<String> NAMES = List.of("hut", "tower", "wall", "bridge", "farm_plot");

    private Templates() {}

    public static int defaultSize(String template) {
        return switch (key(template)) {
            case "hut", "farm_plot" -> 5;
            case "tower" -> 4;
            case "wall" -> 8;
            case "bridge" -> 6;
            default -> 5;
        };
    }

    /** Sizes outside a template's range are clamped into it. */
    public static Blueprint make(String template, int size, String material) throws BlueprintException {
        String id = BlockNames.normalise(material == null ? "" : material);
        if (id.isEmpty()) throw new BlueprintException("material must be a block id such as cobblestone");
        Map<Character, String> legend = Map.of('m', id);
        String t = key(template);
        return switch (t) {
            case "hut" -> hut(clamp(size, 5, 9), legend);
            case "tower" -> tower(clamp(size, 3, 7), legend);
            case "wall" -> wall(clamp(size, 4, 16), legend);
            case "bridge" -> bridge(clamp(size, 4, 16), legend);
            case "farmplot" -> farmPlot(clamp(size, 3, 9), legend);
            default -> throw new BlueprintException("template must be one of " + String.join(", ", NAMES));
        };
    }

    private static String key(String template) {
        String t = template == null ? "" : template.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return t.equals("farm_plot") ? "farmplot" : t;
    }

    private static Blueprint hut(int s, Map<Character, String> legend) {
        List<List<String>> layers = new ArrayList<>();
        for (int y = 0; y < 3; y++) layers.add(ring(s, y < 2));
        layers.add(solid(s));
        return Blueprint.of(layers, legend, 0);
    }

    private static Blueprint tower(int s, Map<Character, String> legend) {
        int h = Math.min(16, 2 * s + 2);
        List<List<String>> layers = new ArrayList<>();
        for (int y = 0; y < h - 1; y++) layers.add(ring(s, y < 2));
        List<String> top = new ArrayList<>();
        for (int row = 0; row < s; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < s; col++) {
                boolean edge = row == 0 || col == 0 || row == s - 1 || col == s - 1;
                sb.append(edge && (row + col) % 2 == 0 ? 'm' : '.');
            }
            top.add(sb.toString());
        }
        layers.add(top);
        return Blueprint.of(layers, legend, 0);
    }

    private static Blueprint wall(int s, Map<Character, String> legend) {
        String row = "m".repeat(s);
        return Blueprint.of(List.of(List.of(row), List.of(row), List.of(row)), legend, 0);
    }

    private static Blueprint bridge(int s, Map<Character, String> legend) {
        List<String> deck = new ArrayList<>();
        List<String> rails = new ArrayList<>();
        for (int row = 0; row < s; row++) {
            deck.add("mmm");
            rails.add("m.m");
        }
        return Blueprint.of(List.of(deck, rails), legend, -1);
    }

    private static Blueprint farmPlot(int s, Map<Character, String> legend) {
        int n = s + 2;
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < n; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < n; col++) sb.append(row == 0 || col == 0 || row == n - 1 || col == n - 1 ? 'm' : ' ');
            rows.add(sb.toString());
        }
        return Blueprint.of(List.of(rows), legend, 0);
    }

    /** An s x s perimeter of m, air inside, with a gap at row 0 in the middle column when {@code gap}. */
    private static List<String> ring(int s, boolean gap) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < s; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < s; col++) {
                boolean edge = row == 0 || col == 0 || row == s - 1 || col == s - 1;
                sb.append(!edge || (gap && row == 0 && col == s / 2) ? '.' : 'm');
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    private static List<String> solid(int s) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < s; row++) rows.add("m".repeat(s));
        return rows;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
