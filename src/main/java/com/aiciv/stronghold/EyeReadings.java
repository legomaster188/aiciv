package com.aiciv.stronghold;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The last few places an eye of ender was thrown and which way it flew; a per-bot attachment. Pure. */
public final class EyeReadings {
    public static final int CAPACITY = 4;

    /** {@code x, z}: the thrower's position, or for a {@code sank} reading where the eye went down. */
    public record Reading(double x, double z, double bearing, boolean sank, long tick) {}

    /** Oldest first. */
    private final List<Reading> all = new ArrayList<>();

    public void add(Reading r) {
        all.add(r);
        while (all.size() > CAPACITY) all.remove(0);
    }

    public List<Reading> all() {
        return List.copyOf(all);
    }

    /**
     * {x, z} of the stronghold: where an eye sank (the newest such reading), else the crossing of the two flights
     * whose bearings differ most (the first pair, widest first, that crosses in front of both throwers).
     */
    public Optional<double[]> estimate() {
        for (int i = all.size() - 1; i >= 0; i--) {
            Reading r = all.get(i);
            if (r.sank()) return Optional.of(new double[] {r.x(), r.z()});
        }
        record Pair(Reading a, Reading b, double spread) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Reading a = all.get(i), b = all.get(j);
                pairs.add(new Pair(a, b, Math.abs(Math.sin(Math.toRadians(b.bearing() - a.bearing())))));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::spread).reversed());
        for (Pair p : pairs) {
            Optional<double[]> hit = Triangulate.intersect(p.a().x(), p.a().z(), p.a().bearing(), p.b().x(), p.b().z(), p.b().bearing());
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
