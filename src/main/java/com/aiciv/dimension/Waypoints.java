package com.aiciv.dimension;

import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Places a bot has found and wants to come back to: portals, a fortress, the stronghold estimate. Each belongs to one
 * dimension. A per-bot attachment ({@code ctx.attachments().get(Waypoints.class, Waypoints::new)}); pure.
 */
public final class Waypoints {
    public static final String PORTAL = "portal";
    public static final String FORTRESS = "fortress";
    public static final String BLAZE_SPAWNER = "blaze_spawner";
    public static final String STRONGHOLD = "stronghold";
    public static final String END_PORTAL = "end_portal";
    public static final String EXIT_PORTAL = "exit_portal";
    public static final String ARRIVAL = "arrival";
    public static final int CAPACITY = 32;
    /** A waypoint of the same kind closer than this is the same place seen again. */
    static final double SAME_PLACE = 8;

    public record Waypoint(String kind, String dimension, Pos pos, String note, long tick) {}

    /** Oldest first. */
    private final List<Waypoint> all = new ArrayList<>();

    /** Remembers a place; one of the same kind within 8 blocks in the same dimension is replaced, past capacity the oldest goes. */
    public void mark(String kind, String dimension, Pos pos, String note, long tick) {
        forget(kind, dimension, pos);
        all.add(new Waypoint(kind, dimension, pos, note == null ? "" : note, tick));
        while (all.size() > CAPACITY) all.remove(0);
    }

    /** Forgets the waypoints of this kind within 8 blocks of {@code pos} in this dimension. */
    public void forget(String kind, String dimension, Pos pos) {
        all.removeIf(w -> w.kind().equals(kind) && w.dimension().equals(dimension) && distance(w.pos(), pos) <= SAME_PLACE);
    }

    public Optional<Waypoint> nearest(String kind, String dimension, Pos from) {
        return all.stream()
                .filter(w -> w.kind().equals(kind) && w.dimension().equals(dimension))
                .min(Comparator.comparingDouble(w -> distance(w.pos(), from)));
    }

    /** Every waypoint in this dimension, oldest first. */
    public List<Waypoint> in(String dimension) {
        return all.stream().filter(w -> w.dimension().equals(dimension)).toList();
    }

    /** Up to {@code n} lines like {@code portal at 10 64 3 (12m N), to the_nether}, nearest first. */
    public List<String> describe(String dimension, Pos from, int n) {
        List<String> out = new ArrayList<>();
        for (Waypoint w : nearestIn(dimension, from, n)) out.add(line(w, from));
        return out;
    }

    /** The waypoints {@link #describe} lists, in the same order. */
    public List<Waypoint> nearestIn(String dimension, Pos from, int n) {
        List<Waypoint> here = new ArrayList<>(in(dimension));
        here.sort(Comparator.comparingDouble(w -> distance(w.pos(), from)));
        return here.subList(0, Math.min(n, here.size()));
    }

    private static String line(Waypoint w, Pos from) {
        Pos p = w.pos();
        StringBuilder sb = new StringBuilder(w.kind()).append(" at ").append(p.x()).append(' ').append(p.y()).append(' ').append(p.z())
                .append(" (").append(Math.round(distance(p, from))).append('m');
        String direction = Compass.direction(p.x() - from.x(), p.z() - from.z());
        if (!direction.equals("here")) sb.append(' ').append(direction);
        sb.append(')');
        if (!w.note().isEmpty()) sb.append(", ").append(w.note());
        return sb.toString();
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
