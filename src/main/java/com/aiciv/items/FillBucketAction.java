package com.aiciv.items;

import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Buckets;
import com.aiciv.world.InventoryOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Scoops a water or lava source into an empty bucket. */
public final class FillBucketAction extends ReachAction {
    static final int RADIUS = 8;
    private static final double[] RADII = {REACH, 3.0, 2.0};

    private final String fluid;
    private final BlockPos given;
    private int attempt;

    public FillBucketAction(String fluid, BlockPos sourceOrNull) {
        this.fluid = fluid;
        this.given = sourceOrNull;
    }

    private TagKey<Fluid> tag() {
        return fluid.equals("lava") ? FluidTags.LAVA : FluidTags.WATER;
    }

    private boolean isSource(BlockState s) {
        return s.getFluidState().isSource() && s.getFluidState().is(tag());
    }

    @Override
    protected String prepare(AiPlayer p) {
        if (!InventoryOps.has(p, Items.BUCKET, 1)) return "needs an empty bucket";
        if (given != null) {
            if (!isSource(p.level().getBlockState(given))) return "no " + fluid + " source at " + coords(given);
            target = given;
            return null;
        }
        target = nearestVisibleSource(p);
        if (target == null) return "no " + fluid + " source in sight within " + RADIUS + " blocks";
        return null;
    }

    /**
     * The nearest source with open air or fluid beside it that a ray from the eyes reaches just below its surface. The
     * snapshot's scan aims at block centres, which a shoreline cell hides behind the bank, although a player sees its surface.
     */
    private BlockPos nearestVisibleSource(AiPlayer p) {
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        BlockPos origin = p.blockPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    q.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    Vec3 surface = new Vec3(q.getX() + 0.5, q.getY() + 0.85, q.getZ() + 0.5);
                    double d2 = eyes.distanceToSqr(surface);
                    if (d2 > (double) RADIUS * RADIUS || d2 >= bestD2 || !level.isLoaded(q) || !isSource(level.getBlockState(q))) continue;
                    if (!level.getBlockState(q.above()).isAir() && !level.getBlockState(q.above()).getFluidState().is(tag())) continue;
                    BlockHitResult hit = level.clip(new ClipContext(eyes, surface, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                    if (hit.getType() != HitResult.Type.MISS) continue;
                    best = q.immutable();
                    bestD2 = d2;
                }
            }
        }
        return best;
    }

    @Override
    protected ActionStatus arrived(BotContext ctx) {
        BlockUse.Result r = Buckets.fill(ctx.player(), target);
        switch (r) {
            case USED:
                done = "filled a " + fluid + "_bucket";
                return ActionStatus.DONE;
            case OUT_OF_REACH:
                if (++attempt < RADII.length) return retryCloser(RADII[attempt]);
                return ActionStatus.failed("too far from the " + fluid + " at " + coords(target));
            case NOT_VISIBLE:
                if (++attempt < RADII.length) return retryCloser(RADII[attempt]);
                return ActionStatus.failed("can't reach the " + fluid + " source at " + coords(target) + " from here");
            default:
                return ActionStatus.failed("could not scoop the " + fluid + " at " + coords(target));
        }
    }

    @Override
    protected String label() {
        return "fill_bucket " + fluid;
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
