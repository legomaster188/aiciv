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
import net.minecraft.world.level.material.FluidState;
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

    /** Empties a {@code water_bucket} or {@code lava_bucket} into {@code cell}, clicking a solid face beside it. */
    public static BlockUse.Result pour(AiPlayer p, BlockPos cell, Item filledBucket) {
        ServerLevel level = p.level();
        Fluid fluid = filledBucket == Items.WATER_BUCKET ? Fluids.WATER : filledBucket == Items.LAVA_BUCKET ? Fluids.LAVA : null;
        if (fluid == null || !InventoryOps.has(p, filledBucket, 1)) return BlockUse.Result.NO_EFFECT;
        BlockState here = level.getBlockState(cell);
        if (!here.canBeReplaced() && here.getFluidState().isEmpty()) return BlockUse.Result.NO_EFFECT;
        Direction face = null;
        boolean supported = false;
        for (Direction d : BlockPlacer.FACE_ORDER) {
            if (level.getBlockState(cell.relative(d)).canBeReplaced()) continue;
            supported = true;
            if (BlockPlacer.canSeeFace(p, cell, d)) {
                face = d;
                break;
            }
        }
        if (!supported) return BlockUse.Result.NO_EFFECT;
        if (face == null) return BlockUse.Result.NOT_VISIBLE;
        Vec3 aim = Vec3.atCenterOf(cell).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double reach = p.blockInteractionRange();
        if (p.getEyePosition().distanceToSqr(aim) > reach * reach) return BlockUse.Result.OUT_OF_REACH;
        if (!ItemUse.select(p, s -> s.is(filledBucket))) return BlockUse.Result.NO_EFFECT;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
        p.gameMode.useItem(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND);
        FluidState now = level.getFluidState(cell);
        return now.isSourceOfType(fluid) ? BlockUse.Result.USED : BlockUse.Result.NO_EFFECT;
    }

    private static int filled(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(Items.WATER_BUCKET) || s.is(Items.LAVA_BUCKET));
    }
}
