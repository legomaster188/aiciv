package com.aiciv.nether;

import com.aiciv.building.Blueprint;
import com.aiciv.building.BlueprintException;
import com.aiciv.building.Facing;
import com.aiciv.nav.Pos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The vertical 4 wide, 5 tall nether portal frame: where its cells are, in the order they are cast. Pure. */
public final class PortalFrame {
    public static final int WIDTH = 4;
    public static final int HEIGHT = 5;
    public static final int OBSIDIAN = 10;
    public static final int CORNERS = 4;
    /** The wall behind a cast frame: 4 wide and 5 tall, one block thick. */
    public static final int WALL_BLOCKS = WIDTH * HEIGHT;
    private static final int OBSIDIAN_WITH_CORNERS = OBSIDIAN + CORNERS;
    private static final String[] LAYERS = {"cooc", "o..o", "o..o", "o..o", "cooc"};
    /** What a bot may spend on corners the portal does not need, best first. */
    private static final List<String> THROWAWAY = List.of("cobblestone", "cobbled_deepslate", "dirt", "netherrack");

    private PortalFrame() {}

    /** The frame as a blueprint: obsidian on the edge, {@code c} for the four corners, air inside. */
    public static Blueprint blueprint(boolean obsidianCorners) {
        return blueprint(obsidianCorners ? "obsidian" : "cobblestone");
    }

    /** As {@link #blueprint(boolean)} with any block for the corners. */
    public static Blueprint blueprint(String cornerBlock) {
        JsonArray layers = new JsonArray();
        for (String row : LAYERS) {
            JsonArray layer = new JsonArray();
            layer.add(row);
            layers.add(layer);
        }
        JsonObject legend = new JsonObject();
        legend.addProperty("o", "obsidian");
        legend.addProperty("c", cornerBlock);
        try {
            return Blueprint.parse(layers, legend);
        } catch (BlueprintException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The 10 obsidian cells: bottom row left to right, left column up, right column up, top row left to right. */
    public static List<Pos> frameCells(Pos bottomLeft, Facing facing) {
        List<Pos> out = new ArrayList<>();
        for (int c = 1; c <= 2; c++) out.add(at(bottomLeft, facing, c, 0));
        for (int y = 1; y <= 3; y++) out.add(at(bottomLeft, facing, 0, y));
        for (int y = 1; y <= 3; y++) out.add(at(bottomLeft, facing, 3, y));
        for (int c = 1; c <= 2; c++) out.add(at(bottomLeft, facing, c, 4));
        return out;
    }

    /** The four corner cells: bottom left, bottom right, top left, top right. */
    public static List<Pos> corners(Pos bottomLeft, Facing facing) {
        return List.of(at(bottomLeft, facing, 0, 0), at(bottomLeft, facing, 3, 0), at(bottomLeft, facing, 0, 4), at(bottomLeft, facing, 3, 4));
    }

    /** The 2 by 3 cells inside the frame, bottom row first. */
    public static List<Pos> interior(Pos bottomLeft, Facing facing) {
        List<Pos> out = new ArrayList<>();
        for (int y = 1; y <= 3; y++) for (int c = 1; c <= 2; c++) out.add(at(bottomLeft, facing, c, y));
        return out;
    }

    /** Obsidian when the bot holds 14, else a throwaway it holds 4 of, else null. */
    public static String cornerBlock(Map<String, Integer> inventory) {
        if (inventory.getOrDefault("obsidian", 0) >= OBSIDIAN_WITH_CORNERS) return "obsidian";
        return throwaway(inventory);
    }

    /** A cheap block the bot holds at least 4 of: cobblestone, cobbled deepslate, dirt, netherrack, then any planks. */
    public static String throwaway(Map<String, Integer> inventory) {
        for (String id : THROWAWAY) if (inventory.getOrDefault(id, 0) >= CORNERS) return id;
        return inventory.entrySet().stream()
                .filter(e -> e.getKey().endsWith("_planks") && e.getValue() >= CORNERS)
                .map(Map.Entry::getKey).sorted().findFirst().orElse(null);
    }

    /** A one-block-thick wall of {@code block} the size of the frame, for the cast to pour against. */
    public static Blueprint wallBlueprint(String block) {
        JsonArray layers = new JsonArray();
        for (int y = 0; y < HEIGHT; y++) {
            JsonArray layer = new JsonArray();
            layer.add("wwww");
            layers.add(layer);
        }
        JsonObject legend = new JsonObject();
        legend.addProperty("w", block);
        try {
            return Blueprint.parse(layers, legend);
        } catch (BlueprintException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The cells of that wall: one step behind the frame, in the direction the builder faces. */
    public static List<Pos> wallCells(Pos bottomLeft, Facing facing) {
        List<Pos> out = new ArrayList<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int c = 0; c < WIDTH; c++) {
                Pos p = at(bottomLeft, facing, c, y);
                out.add(new Pos(p.x() + facing.forwardX(), p.y(), p.z() + facing.forwardZ()));
            }
        }
        return out;
    }

    /** A throwaway the bot holds at least 20 of, for the wall; never planks or anything else the lava could set alight. */
    public static String wallBlock(Map<String, Integer> inventory) {
        for (String id : THROWAWAY) if (inventory.getOrDefault(id, 0) >= WALL_BLOCKS) return id;
        return null;
    }

    private static Pos at(Pos bottomLeft, Facing facing, int column, int y) {
        return new Pos(bottomLeft.x() + column * facing.rightX(), bottomLeft.y() + y, bottomLeft.z() + column * facing.rightZ());
    }
}
