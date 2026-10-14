package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Places a block against a neighbouring face, the way a survival player right-clicks. */
public final class BlockPlacer {
    public enum PlaceResult { PLACED, NO_ITEM, OCCUPIED, NO_SUPPORT, OUT_OF_REACH, NOT_VISIBLE, REFUSED }

    /** Below first: bridging and pillaring both lean on the block underneath. */
    static final Direction[] FACE_ORDER = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private BlockPlacer() {}

    public static PlaceResult place(AiPlayer p, BlockPos target, Predicate<ItemStack> item) {
        ServerLevel level = (ServerLevel) p.level();
        if (!level.getBlockState(target).canBeReplaced()) return PlaceResult.OCCUPIED;
        if (!level.mayInteract(p, target)) return PlaceResult.REFUSED;
        Direction face = null;
        boolean supported = false;
        for (Direction d : FACE_ORDER) {
            if (level.getBlockState(target.relative(d)).canBeReplaced()) continue;
            supported = true;
            if (canSeeFace(p, target, d)) {
                face = d;
                break;
            }
        }
        if (!supported) return PlaceResult.NO_SUPPORT;
        if (face == null) return PlaceResult.NOT_VISIBLE;
        return clickFace(p, level, target, face, item);
    }

    /**
     * Like {@link #place}, but only against the neighbour on {@code side} ({@code target.relative(side)}), clicking its
     * face toward the target: for blocks whose placement depends on the clicked face (wall torches, ladders, buttons).
     */
    public static PlaceResult placeAgainst(AiPlayer p, BlockPos target, Direction side, Predicate<ItemStack> item) {
        ServerLevel level = (ServerLevel) p.level();
        if (!level.getBlockState(target).canBeReplaced()) return PlaceResult.OCCUPIED;
        if (!level.mayInteract(p, target)) return PlaceResult.REFUSED;
        if (level.getBlockState(target.relative(side)).canBeReplaced()) return PlaceResult.NO_SUPPORT;
        if (!canSeeFace(p, target, side)) return PlaceResult.NOT_VISIBLE;
        return clickFace(p, level, target, side, item);
    }

    /** Right-clicks the face of {@code target.relative(face)} that touches the target, sneaking, holding the item. */
    private static PlaceResult clickFace(AiPlayer p, ServerLevel level, BlockPos target, Direction face, Predicate<ItemStack> item) {
        BlockPos neighbour = target.relative(face);
        Vec3 hit = Vec3.atCenterOf(target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double reach = p.blockInteractionRange();
        if (p.getEyePosition().distanceToSqr(hit) > reach * reach) return PlaceResult.OUT_OF_REACH;
        if (!InventoryOps.selectBest(p, s -> item.test(s) ? 1 : 0)) return PlaceResult.NO_ITEM;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
        ItemStack stack = p.getItemInHand(InteractionHand.MAIN_HAND);
        // Sneaking keeps the click from using the neighbour (opening a table, toggling a lever) instead of placing.
        boolean wasSneaking = p.isShiftKeyDown();
        p.setShiftKeyDown(true);
        try {
            p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face.getOpposite(), neighbour, false));
        } finally {
            p.setShiftKeyDown(wasSneaking);
        }
        return level.getBlockState(target).canBeReplaced() ? PlaceResult.REFUSED : PlaceResult.PLACED;
    }

    /**
     * Whether a ray from the eyes reaches the neighbour's face that touches the target (aimed just inside the neighbour),
     * so a block is never placed against a face behind a wall. A replaceable plant in the target cell may catch the ray.
     */
    static boolean canSeeFace(AiPlayer p, BlockPos target, Direction face) {
        Vec3 aim = Vec3.atCenterOf(target).add(face.getStepX() * 0.55, face.getStepY() * 0.55, face.getStepZ() * 0.55);
        BlockHitResult ray = p.level().clip(new ClipContext(p.getEyePosition(), aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (ray.getType() != HitResult.Type.BLOCK) return false;
        BlockPos hit = ray.getBlockPos();
        return hit.equals(target.relative(face)) || hit.equals(target);
    }

    /** Puts the item on a free floor cell within 2 blocks of the bot's feet (not its own), nearest first. Null when none works. */
    public static BlockPos placeNearby(AiPlayer p, Predicate<ItemStack> item) {
        ServerLevel level = (ServerLevel) p.level();
        BlockPos feet = p.blockPosition();
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos cell = feet.offset(dx, 0, dz);
                if (level.getBlockState(cell).canBeReplaced() && level.getFluidState(cell).isEmpty()
                        && !level.getBlockState(cell.below()).canBeReplaced()) {
                    cells.add(cell);
                }
            }
        }
        cells.sort(Comparator.comparingDouble(c -> c.distSqr(feet)));
        for (BlockPos cell : cells) {
            if (place(p, cell, item) == PlaceResult.PLACED) return cell;
        }
        return null;
    }
}
