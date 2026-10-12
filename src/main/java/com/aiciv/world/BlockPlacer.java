package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Places a block against a neighbouring face, the way a survival player right-clicks. */
public final class BlockPlacer {
    public enum PlaceResult { PLACED, NO_ITEM, OCCUPIED, NO_SUPPORT, OUT_OF_REACH, REFUSED }

    /** Below first: bridging and pillaring both lean on the block underneath. */
    private static final Direction[] FACE_ORDER = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private BlockPlacer() {}

    public static PlaceResult place(AiPlayer p, BlockPos target, Predicate<ItemStack> item) {
        ServerLevel level = (ServerLevel) p.level();
        if (!level.getBlockState(target).canBeReplaced()) return PlaceResult.OCCUPIED;
        if (!level.mayInteract(p, target)) return PlaceResult.REFUSED;
        Direction face = null;
        for (Direction d : FACE_ORDER) {
            if (!level.getBlockState(target.relative(d)).canBeReplaced()) {
                face = d;
                break;
            }
        }
        if (face == null) return PlaceResult.NO_SUPPORT;
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
}
