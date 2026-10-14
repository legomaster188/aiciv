package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Filling and emptying buckets the way a player does: looking at the fluid or at a face beside the cell, within reach. */
public final class Buckets {
    private Buckets() {}

    /** Scoops the fluid source at {@code source} into an empty bucket. */
    public static BlockUse.Result fill(AiPlayer p, BlockPos source) {
        ServerLevel level = p.level();
        if (!InventoryOps.has(p, Items.BUCKET, 1)) return BlockUse.Result.NO_EFFECT;
        if (!level.getFluidState(source).isSource()) return BlockUse.Result.NO_EFFECT;
        Vec3 eyes = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(source);
        double reach = p.blockInteractionRange();
        if (eyes.distanceToSqr(centre) > reach * reach) return BlockUse.Result.OUT_OF_REACH;
        // The bucket's own ray stops at the first source block, so this one must be the first on the line.
        BlockHitResult hit = level.clip(new ClipContext(eyes, centre, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(source)) return BlockUse.Result.NOT_VISIBLE;
        int before = filled(p);
        if (!ItemUse.select(p, s -> s.is(Items.BUCKET))) return BlockUse.Result.NO_EFFECT;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, centre);
        p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
        return filled(p) > before ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }

    private static final Direction[] FACE_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    /** The test ray runs this far past the face so it crosses it. */
    private static final double OVERSHOOT = 0.3;

    /** What a pour from {@code eyes} would aim at: the point, or why there is none. */
    private record Plan(Vec3 aim, boolean supported, boolean inReach) {}

    /**
     * A bucket goes where the crosshair points, so the bot must look at the face of a neighbour that touches the cell,
     * and the first thing the look ray meets has to be that very face: a corner in the way would make the fluid land
     * beside the corner instead.
     */
    private static Plan plan(ServerLevel level, AiPlayer p, Vec3 eyes, BlockPos cell) {
        double reach = p.blockInteractionRange();
        boolean supported = false, inReach = false;
        for (Direction d : FACE_ORDER) {
            BlockPos neighbour = cell.relative(d);
            if (level.getBlockState(neighbour).canBeReplaced()) continue;
            supported = true;
            Vec3 face = Vec3.atCenterOf(cell).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            if (eyes.distanceToSqr(face) > reach * reach) continue;
            inReach = true;
            Vec3 past = face.add(face.subtract(eyes).normalize().scale(OVERSHOOT));
            BlockHitResult hit = level.clip(new ClipContext(eyes, past, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(neighbour) && hit.getDirection() == d.getOpposite()) {
                return new Plan(face, true, true);
            }
        }
        return new Plan(null, supported, inReach);
    }

    /** Whether a bot with its eyes at {@code eyes} could pour into {@code cell}. */
    public static boolean canPourFrom(AiPlayer p, Vec3 eyes, BlockPos cell) {
        ServerLevel level = p.level();
        BlockState here = level.getBlockState(cell);
        return (here.canBeReplaced() || !here.getFluidState().isEmpty()) && plan(level, p, eyes, cell).aim() != null;
    }

    /** Empties a {@code water_bucket} or {@code lava_bucket} into {@code cell}, clicking a solid face beside it. */
    public static BlockUse.Result pour(AiPlayer p, BlockPos cell, Item filledBucket) {
        ServerLevel level = p.level();
        Fluid fluid = filledBucket == Items.WATER_BUCKET ? Fluids.WATER : filledBucket == Items.LAVA_BUCKET ? Fluids.LAVA : null;
        if (fluid == null || !InventoryOps.has(p, filledBucket, 1)) return BlockUse.Result.NO_EFFECT;
        BlockState here = level.getBlockState(cell);
        if (!here.canBeReplaced() && here.getFluidState().isEmpty()) return BlockUse.Result.NO_EFFECT;
        Plan plan = plan(level, p, p.getEyePosition(), cell);
        if (!plan.supported()) return BlockUse.Result.NO_EFFECT;
        if (!plan.inReach()) return BlockUse.Result.OUT_OF_REACH;
        if (plan.aim() == null) return BlockUse.Result.NOT_VISIBLE;
        if (!ItemUse.select(p, s -> s.is(filledBucket))) return BlockUse.Result.NO_EFFECT;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, plan.aim());
        p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
        return level.getFluidState(cell).isSourceOfType(fluid) ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }

    private static int filled(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(Items.WATER_BUCKET) || s.is(Items.LAVA_BUCKET));
    }
}
