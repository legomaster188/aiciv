package com.aiciv.nether;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelBlockView;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a cell near a work site where the bot can stand and the eyes there satisfy a rule (a clear look at the face it
 * needs). Cells above the ground count too when the bot has blocks to climb on: the navigator pillars up to them.
 */
final class StandingSpot {
    static final int RANGE = 5;
    static final int BELOW = 2;
    static final int ABOVE = 4;
    /** Pillar blocks cost more than steps: a spot on the ground wins unless the climb is needed for the view. */
    private static final double CLIMB_COST = 25;
    private static final double EYE_HEIGHT = 1.62;

    private StandingSpot() {}

    /**
     * The cheapest cell within {@link #RANGE} of {@code around}, outside {@code keepOut} and {@code bad}, with room for the
     * body and either a floor or a short drop of air above one (at most {@code spare} blocks to pillar), whose eyes pass
     * {@code rule}.
     */
    static Optional<BlockPos> find(AiPlayer p, BlockPos around, Set<BlockPos> keepOut, Set<BlockPos> bad, int spare, Predicate<Vec3> rule) {
        ServerLevel level = p.level();
        LevelBlockView view = new LevelBlockView(level);
        BlockPos me = p.blockPosition();
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dz = -RANGE; dz <= RANGE; dz++) {
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    BlockPos f = around.offset(dx, dy, dz);
                    if (keepOut.contains(f) || keepOut.contains(f.above()) || bad.contains(f)) continue;
                    if (!level.isLoaded(f) || !free(view, f) || !free(view, f.above())) continue;
                    int climb = climb(view, f, keepOut, spare);
                    if (climb < 0) continue;
                    double cost = f.distSqr(me) + CLIMB_COST * climb;
                    if (cost >= bestCost) continue;
                    if (!rule.test(new Vec3(f.getX() + 0.5, f.getY() + EYE_HEIGHT, f.getZ() + 0.5))) continue;
                    best = f;
                    bestCost = cost;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean free(LevelBlockView view, BlockPos c) {
        return view.passable(c.getX(), c.getY(), c.getZ()) && !view.dangerous(c.getX(), c.getY(), c.getZ());
    }

    /** Blocks to pillar to stand at {@code f}: 0 on a floor, 1 to {@code spare} over air, -1 when it cannot be done. */
    private static int climb(LevelBlockView view, BlockPos f, Set<BlockPos> keepOut, int spare) {
        for (int k = 0; k <= spare; k++) {
            BlockPos under = f.below(k + 1);
            if (view.standable(under.getX(), under.getY(), under.getZ()) && !view.dangerous(under.getX(), under.getY(), under.getZ())) return k;
            // The pillar goes up through this cell, so it must be open air outside the portal site.
            if (!free(view, under) || keepOut.contains(under)) return -1;
        }
        return -1;
    }
}
