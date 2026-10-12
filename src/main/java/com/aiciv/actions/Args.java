package com.aiciv.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Forgiving readers for a model's tool-call arguments. */
public final class Args {
    static final int WORLD_LIMIT = 29_999_984;

    private Args() {}

    public static String str(JsonObject a, String k) {
        JsonElement e = a.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** A whole number from a JSON number or numeric string; fractions round down to the containing block. */
    public static Integer integer(JsonObject a, String k) {
        JsonElement e = a.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return (int) Math.floor(d);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** {@code key} clamped to min..max, or {@code def} when it is missing or not a number. */
    public static int intIn(JsonObject a, String key, int def, int min, int max) {
        Integer v = integer(a, key);
        return v == null ? def : Math.max(min, Math.min(max, v));
    }

    /** The x/y/z block position; empty when they are absent (or unusable; check {@link #posProblem} first). */
    public static Optional<BlockPos> optionalPos(JsonObject a) {
        Integer x = integer(a, "x"), y = integer(a, "y"), z = integer(a, "z");
        if (x == null || y == null || z == null) return Optional.empty();
        return Optional.of(new BlockPos(x, y, z));
    }

    /** Null when x, y and z are all given and valid or all absent; otherwise what is wrong, for an Invalid result. */
    public static String posProblem(String action, JsonObject a) {
        int present = 0;
        for (String k : new String[] {"x", "y", "z"}) if (a.has(k) && !a.get(k).isJsonNull()) present++;
        if (present == 0) return null;
        if (present < 3) return action + " needs all of x, y and z, or none";
        Integer x = integer(a, "x"), y = integer(a, "y"), z = integer(a, "z");
        if (x == null || y == null || z == null) return action + " needs whole numbers x, y and z";
        return rangeProblem(action, x, y, z);
    }

    /** The y and world-border checks shared with the core coordinate actions. */
    static String rangeProblem(String action, int x, int y, int z) {
        if (y < -64 || y > 320) return action + " y must be between -64 and 320";
        if (x < -WORLD_LIMIT || x > WORLD_LIMIT || z < -WORLD_LIMIT || z > WORLD_LIMIT) {
            return action + " x and z must be inside the world border";
        }
        return null;
    }
}
