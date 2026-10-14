package com.aiciv.building;

import com.aiciv.nav.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** A blueprint laid onto world coordinates. Pure: knows nothing of the world beyond what callers pass in. */
public final class BuildPlan {
    /** {@code props} are block states with relative facings already resolved to absolute directions. */
    public record Cell(Pos pos, String block, Map<String, String> props) {
        public Cell(Pos pos, String block) {
            this(pos, block, Map.of());
        }
    }

    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final List<Cell> places = new ArrayList<>();
    private final List<Pos> mustBeAir = new ArrayList<>();
    private final Pos min, max;

    private BuildPlan(Blueprint bp, Pos origin, Facing f) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int y = 0; y < bp.height(); y++) {
            for (int row = 0; row < bp.depth(); row++) {
                for (int col = 0; col < bp.width(); col++) {
                    Pos p = new Pos(origin.x() + col * f.rightX() + row * f.forwardX(), origin.y() + y + bp.yOffset(),
                            origin.z() + col * f.rightZ() + row * f.forwardZ());
                    minX = Math.min(minX, p.x());
                    maxX = Math.max(maxX, p.x());
                    minY = Math.min(minY, p.y());
                    maxY = Math.max(maxY, p.y());
                    minZ = Math.min(minZ, p.z());
                    maxZ = Math.max(maxZ, p.z());
                    char symbol = bp.cells()[y][row][col];
                    if (symbol == '.') {
                        mustBeAir.add(p);
                        continue;
                    }
                    String block = bp.legend().get(symbol);
                    if (block == null) continue;
                    // The cell above a door's lower half is the door's own upper half, placed together with it.
                    if (isDoor(block) && y > 0 && bp.cells()[y - 1][row][col] == symbol) continue;
                    places.add(new Cell(p, block, resolve(bp.states().getOrDefault(symbol, Map.of()), f)));
                }
            }
        }
        this.min = new Pos(minX, minY, minZ);
        this.max = new Pos(maxX, maxY, maxZ);
    }

    /** forward/back/left/right (relative to the way the builder faces) become north/east/south/west. */
    private static Map<String, String> resolve(Map<String, String> props, Facing f) {
        String facing = props.get("facing");
        if (facing == null) return props;
        int dx, dz;
        switch (facing) {
            case "forward" -> { dx = f.forwardX(); dz = f.forwardZ(); }
            case "back" -> { dx = -f.forwardX(); dz = -f.forwardZ(); }
            case "right" -> { dx = f.rightX(); dz = f.rightZ(); }
            case "left" -> { dx = -f.rightX(); dz = -f.rightZ(); }
            default -> { return props; }
        }
        Map<String, String> out = new LinkedHashMap<>(props);
        out.put("facing", dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north");
        return out;
    }

    public static BuildPlan of(Blueprint bp, Pos origin, Facing facing) {
        return new BuildPlan(bp, origin, facing);
    }

    /** Bottom-up, then by row, then by column. */
    public List<Cell> places() {
        return places;
    }

    public List<Pos> mustBeAir() {
        return mustBeAir;
    }

    public Pos min() {
        return min;
    }

    public Pos max() {
        return max;
    }

    /**
     * Items needed per block id, in blueprint order, leaving out cells that already hold that block. A door or bed is
     * one item for its two cells.
     */
    public Map<String, Integer> materials(Function<Pos, String> blockAt) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Cell c : places) {
            if (c.block().equals(blockAt.apply(c.pos()))) continue;
            out.merge(c.block(), 1, Integer::sum);
        }
        // A bed is two cells of the blueprint but one item.
        out.replaceAll((block, n) -> block.endsWith("_bed") ? (n + 1) / 2 : n);
        return out;
    }

    /**
     * Planned cells with no path of planned cells to a solid world block. They could not be placed against anything
     * until something else is built, so the blueprint needs support for them.
     */
    public List<Cell> floating(Predicate<Pos> solidInWorld) {
        Map<Pos, Cell> byPos = new LinkedHashMap<>();
        for (Cell c : places) byPos.put(c.pos(), c);
        Set<Pos> reached = new HashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        for (Pos p : byPos.keySet()) {
            for (int[] s : STEPS) {
                if (solidInWorld.test(new Pos(p.x() + s[0], p.y() + s[1], p.z() + s[2]))) {
                    if (reached.add(p)) queue.add(p);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int[] s : STEPS) {
                Pos n = new Pos(p.x() + s[0], p.y() + s[1], p.z() + s[2]);
                if (byPos.containsKey(n) && reached.add(n)) queue.add(n);
            }
        }
        List<Cell> out = new ArrayList<>();
        for (Cell c : places) if (!reached.contains(c.pos())) out.add(c);
        return out;
    }

    /** A door needs room for its upper half: ' ' (or its own symbol) above it, never air. Null when fine. */
    public static String doorProblem(Blueprint bp) {
        for (int y = 0; y < bp.height(); y++) {
            for (int row = 0; row < bp.depth(); row++) {
                for (int col = 0; col < bp.width(); col++) {
                    char symbol = bp.cells()[y][row][col];
                    String block = bp.legend().get(symbol);
                    if (block == null || !isDoor(block)) continue;
                    char above = bp.at(y + 1, row, col);
                    if (above != ' ' && above != symbol) {
                        return "door at layer " + (y + 1) + " row " + (row + 1) + " col " + (col + 1) + " needs ' ' above it";
                    }
                }
            }
        }
        return null;
    }

    private static boolean isDoor(String block) {
        return block.endsWith("_door");
    }
}
