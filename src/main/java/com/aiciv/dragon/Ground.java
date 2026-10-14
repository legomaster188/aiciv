package com.aiciv.dragon;

import com.aiciv.nav.BlockView;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.world.phys.Vec3;

/** Finds solid footing around a point, so a step sideways never ends over the void or in a fire. */
final class Ground {
    /** How far below the reference height a floor may be: more than this is a drop, or the void. */
    static final int MAX_DROP = 3;
    private static final int STEPS = 24;

    private Ground() {}

    /** The feet height at (x, z) nearest {@code refY} (up one, down three) with a floor to stand on and room for the body. */
    static OptionalInt floorY(BlockView view, int x, int refY, int z) {
        int[] order = {0, 1, -1, -2, -3};
        for (int dy : order) {
            int y = refY + dy;
            if (view.standable(x, y - 1, z) && !view.dangerous(x, y - 1, z)
                    && view.passable(x, y, z) && !view.dangerous(x, y, z)
                    && view.passable(x, y + 1, z) && !view.dangerous(x, y + 1, z)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Standing points on a ring of {@code radius} around (cx, cz), nearest to the direction {@code baseAngle} (radians,
     * atan2(dz, dx)) first. Cell centres, at the height of their floor.
     */
    static List<Vec3> ring(BlockView view, double cx, double cz, double radius, double baseAngle, int refY) {
        List<Vec3> out = new ArrayList<>();
        for (int k = 0; k <= STEPS / 2; k++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (k == 0 && sign == -1) continue;
                if (k == STEPS / 2 && sign == -1) continue;
                double angle = baseAngle + sign * k * (2 * Math.PI / STEPS);
                int x = (int) Math.floor(cx + radius * Math.cos(angle));
                int z = (int) Math.floor(cz + radius * Math.sin(angle));
                OptionalInt y = floorY(view, x, refY, z);
                if (y.isPresent()) out.add(new Vec3(x + 0.5, y.getAsInt(), z + 0.5));
            }
        }
        return out;
    }
}
