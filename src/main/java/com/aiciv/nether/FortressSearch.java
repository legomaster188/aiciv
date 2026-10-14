package com.aiciv.nether;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Where to walk when looking for a nether fortress, and what counts as one. Pure. */
public final class FortressSearch {
    private static final String[] COMPASS = {"N", "E", "S", "W"};
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final Set<String> BLOCKS = Set.of("nether_bricks", "nether_brick_fence", "nether_brick_stairs", "nether_brick_slab",
            "nether_brick_wall", "cracked_nether_bricks", "chiseled_nether_bricks");

    private FortressSearch() {}

    /**
     * Corners of a square spiral: legs of {@code L, L, 2L, 2L, 3L, 3L ...} blocks turning clockwise from
     * {@code firstDirection} (N, E, S or W), until the legs add up to {@code budget}; the last leg is cut to fit.
     */
    public static List<int[]> spiral(int startX, int startZ, String firstDirection, int legLength, int budget) {
        int dir = 0;
        String wanted = firstDirection == null ? "N" : firstDirection.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < COMPASS.length; i++) if (COMPASS[i].equals(wanted)) dir = i;
        List<int[]> out = new ArrayList<>();
        int x = startX, z = startZ, spent = 0;
        for (int leg = 0; spent < budget && leg < 1000; leg++) {
            int length = Math.min(legLength * (leg / 2 + 1), budget - spent);
            x += STEP[dir][0] * length;
            z += STEP[dir][1] * length;
            spent += length;
            out.add(new int[] {x, z});
            dir = (dir + 1) % 4;
        }
        return out;
    }

    public static boolean isFortressBlock(String id) {
        return BLOCKS.contains(id);
    }
}
