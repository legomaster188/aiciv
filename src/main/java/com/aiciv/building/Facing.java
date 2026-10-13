package com.aiciv.building;

import java.util.Locale;
import net.minecraft.core.Direction;

/**
 * The way the builder faces. Row 0 of a blueprint is nearest the builder and columns run left to right as the builder
 * sees them, so (forward, right) is the world step for one row and one column.
 */
public enum Facing {
    NORTH(0, -1, 1, 0),
    EAST(1, 0, 0, 1),
    SOUTH(0, 1, -1, 0),
    WEST(-1, 0, 0, -1);

    private final int fx, fz, rx, rz;

    Facing(int fx, int fz, int rx, int rz) {
        this.fx = fx;
        this.fz = fz;
        this.rx = rx;
        this.rz = rz;
    }

    public int forwardX() {
        return fx;
    }

    public int forwardZ() {
        return fz;
    }

    public int rightX() {
        return rx;
    }

    public int rightZ() {
        return rz;
    }

    /** n, north, N ... ; null for anything else. */
    public static Facing parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "n", "north" -> NORTH;
            case "e", "east" -> EAST;
            case "s", "south" -> SOUTH;
            case "w", "west" -> WEST;
            default -> null;
        };
    }

    public static Facing of(Direction d) {
        return switch (d) {
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> NORTH;
        };
    }
}
