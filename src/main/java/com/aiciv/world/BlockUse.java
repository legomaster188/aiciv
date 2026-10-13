package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Right-clicks a block the way a survival player does: within reach, on a face the eyes can see, never through walls. */
public final class BlockUse {
    public enum Result { USED, OUT_OF_REACH, NOT_VISIBLE, NO_EFFECT }

    /** Aim points sit just inside each face, as {@link Sight} aims. */
    private static final double FACE = 0.45;

    private BlockUse() {}

    /** Clicks the visible face nearest the eyes, holding whatever is in the main hand, not sneaking. */
    public static Result click(AiPlayer p, BlockPos pos) {
        return clickAmong(p, pos, List.of(Direction.values()));
    }

    /** Clicks only {@code face} (tilling and planting click the top). */
    public static Result clickFace(AiPlayer p, BlockPos pos, Direction face) {
        return clickAmong(p, pos, List.of(face));
    }

    /**
     * Selects an empty hotbar slot, else one holding something that is not a block, so a click uses the block instead of
     * placing against it. False when every hotbar slot holds a block item.
     */
    public static boolean withFreeHand(AiPlayer p) {
        Inventory inv = p.getInventory();
        int notBlock = -1;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                inv.setSelectedSlot(i);
                return true;
            }
            if (notBlock < 0 && !(s.getItem() instanceof BlockItem)) notBlock = i;
        }
        if (notBlock < 0) return false;
        inv.setSelectedSlot(notBlock);
        return true;
    }

    private static Result clickAmong(AiPlayer p, BlockPos pos, List<Direction> faces) {
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        double reach = p.blockInteractionRange();
        Direction best = null;
        Vec3 bestAim = null;
        boolean inReach = false;
        for (Direction d : faces) {
            Vec3 aim = centre.add(d.getStepX() * FACE, d.getStepY() * FACE, d.getStepZ() * FACE);
            double d2 = eyes.distanceToSqr(aim);
            if (d2 > reach * reach) continue;
            inReach = true;
            if (!reaches(p, eyes, aim, pos)) continue;
            if (bestAim == null || d2 < eyes.distanceToSqr(bestAim)) {
                best = d;
                bestAim = aim;
            }
        }
        if (!inReach) return Result.OUT_OF_REACH;
        if (best == null) return Result.NOT_VISIBLE;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, bestAim);
        boolean wasSneaking = p.isShiftKeyDown();
        p.setShiftKeyDown(false);
        try {
            InteractionResult r = p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(bestAim, best, pos, false));
            return r.consumesAction() ? Result.USED : Result.NO_EFFECT;
        } finally {
            p.setShiftKeyDown(wasSneaking);
        }
    }

    /** A ray to a point inside the block either ends in it or misses everything (the point lies outside a small shape). */
    private static boolean reaches(AiPlayer p, Vec3 eyes, Vec3 aim, BlockPos pos) {
        BlockHitResult hit = p.level().clip(new ClipContext(eyes, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
}
