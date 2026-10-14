package com.aiciv.stronghold;

import com.aiciv.nav.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Which parts of a maze have been walked through, in cubes of {@code bucket} blocks, and where to go next. Pure. */
public final class Frontier {
    /** Candidates this close to the bot's height are preferred: a stronghold's corridors are mostly level. */
    private static final int SAME_LEVEL = 2;

    public record Cell(int x, int y, int z) {}

    private final int bucket;
    private final Set<Cell> visited = new HashSet<>();

    public Frontier(int bucket) {
        this.bucket = Math.max(1, bucket);
    }

    private Cell cell(Pos p) {
        return new Cell(Math.floorDiv(p.x(), bucket), Math.floorDiv(p.y(), bucket), Math.floorDiv(p.z(), bucket));
    }

    public void visit(Pos p) {
        visited.add(cell(p));
    }

    public boolean visited(Pos p) {
        return visited.contains(cell(p));
    }

    /** The nearest candidate in an unvisited cube, nearest among those within 2 blocks of {@code from}'s height if any. */
    public Optional<Pos> next(Pos from, List<Pos> openAir) {
        Pos best = null, bestLevel = null;
        double bestD = Double.MAX_VALUE, bestLevelD = Double.MAX_VALUE;
        for (Pos c : openAir) {
            if (visited(c)) continue;
            double d = distanceSq(from, c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
            if (Math.abs(c.y() - from.y()) <= SAME_LEVEL && d < bestLevelD) {
                bestLevelD = d;
                bestLevel = c;
            }
        }
        return Optional.ofNullable(bestLevel != null ? bestLevel : best);
    }

    private static double distanceSq(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
