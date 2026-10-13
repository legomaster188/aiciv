package com.aiciv.farm;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds blocks the bot can see that {@code Finder.visibleStates} misses: farmland (its top sits a sixteenth below the
 * aim point of a full block) and crops (no collision shape). A block counts as seen when an outline ray from the eyes to
 * its centre or just above its top ends there or reaches the point without hitting anything.
 */
final class FarmScan {
    private static final int MAX_RAYS = 400;

    private FarmScan() {}

    /** Matching blocks within {@code radius} of the bot that the eyes can see, nearest first. */
    static List<BlockPos> visible(AiPlayer p, Predicate<BlockState> match, int radius) {
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        BlockPos origin = p.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    q.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.isLoaded(q) && match.test(level.getBlockState(q))) candidates.add(q.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> Vec3.atCenterOf(c).distanceToSqr(eyes)));
        List<BlockPos> out = new ArrayList<>();
        int rays = 0;
        for (BlockPos c : candidates) {
            if (rays >= MAX_RAYS) break;
            rays += 2;
            Vec3 centre = Vec3.atCenterOf(c);
            if (reaches(p, eyes, centre, c) || reaches(p, eyes, centre.add(0, 0.45, 0), c)) out.add(c);
        }
        return out;
    }

    private static boolean reaches(AiPlayer p, Vec3 eyes, Vec3 aim, BlockPos pos) {
        BlockHitResult hit = p.level().clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
}
