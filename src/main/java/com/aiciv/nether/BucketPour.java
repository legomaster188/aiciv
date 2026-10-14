package com.aiciv.nether;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
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

/**
 * Empties a water or lava bucket into a cell. A bucket goes where the crosshair points, so the bot must look at the face
 * of a neighbour that touches the cell, and the first thing the look ray meets has to be that very face: a corner in the
 * way would make the fluid land beside the corner instead.
 */
final class BucketPour {
    private static final Direction[] FACE_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    /** The test ray runs this far past the face so it crosses it. */
    private static final double OVERSHOOT = 0.3;

    private BucketPour() {}

    /** What a pour from {@code eyes} would aim at: the point, or why there is none. */
    private record Plan(Vec3 aim, boolean supported, boolean inReach) {}

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
    static boolean canPourFrom(AiPlayer p, Vec3 eyes, BlockPos cell) {
        ServerLevel level = p.level();
        BlockState here = level.getBlockState(cell);
        return (here.canBeReplaced() || !here.getFluidState().isEmpty()) && plan(level, p, eyes, cell).aim() != null;
    }

    static BlockUse.Result pour(AiPlayer p, BlockPos cell, Item filledBucket) {
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
}
