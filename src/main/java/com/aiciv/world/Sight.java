package com.aiciv.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Whether a player could aim at a block: a straight line from the eyes must reach it without passing through another block. */
public final class Sight {
    /** Aim points sit just inside each face, so a ray that arrives ends within the target block. */
    private static final double FACE = 0.45;

    private Sight() {}

    public static boolean canSee(Player player, BlockPos target) {
        return occluder(player, target) == null;
    }

    /**
     * Null when a line from the eyes reaches the target's centre or the centre of any of its faces (a player can aim
     * at any visible part of a block); otherwise the first block in the way of the line to its centre, the one to dig.
     */
    public static BlockPos occluder(Player player, BlockPos target) {
        Vec3 eyes = player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        BlockPos first = blocker(player, eyes, centre, target);
        if (first == null) return null;
        for (Direction d : Direction.values()) {
            Vec3 face = centre.add(d.getStepX() * FACE, d.getStepY() * FACE, d.getStepZ() * FACE);
            if (blocker(player, eyes, face, target) == null) return null;
        }
        return first;
    }

    private static BlockPos blocker(Player player, Vec3 from, Vec3 to, BlockPos target) {
        BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) return null;
        return hit.getBlockPos();
    }
}
