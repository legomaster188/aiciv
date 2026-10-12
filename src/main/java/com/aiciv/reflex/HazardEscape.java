package com.aiciv.reflex;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gets out of lava or fire by walking in a straight line to the nearest safe cell within 6 blocks, preferring
 * water when the bot is burning. It steers the player itself: the pathfinder never plans through lava, which is
 * exactly where this starts.
 */
final class HazardEscape implements Action {
    static final int RADIUS = 6;
    static final int HEIGHT = 3;
    /** A water cell is worth this many blocks of walking, because it also puts the fire out. */
    private static final double WATER_BONUS = 2.0;

    private record Target(BlockPos pos, boolean water) {}

    private Target target;
    private boolean planned;

    /** The bot is in lava or in a fire block, or burning. */
    static boolean hazardous(AiPlayer p) {
        return inHazardBlock(p) || p.isOnFire();
    }

    static boolean inHazardBlock(AiPlayer p) {
        if (p.isInLava()) return true;
        ServerLevel level = p.level();
        BlockPos feet = p.blockPosition();
        return dangerous(level.getBlockState(feet)) || dangerous(level.getBlockState(feet.above()));
    }

    /** Burning with no lava or fire underfoot: only water helps, so a reflex is worth it only if some is close. */
    static boolean waterNearby(AiPlayer p) {
        return find(p, true) != null;
    }

    static boolean dangerous(BlockState s) {
        return s.is(BlockTags.FIRE) || s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.MAGMA_BLOCK);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!planned) {
            planned = true;
            target = find(p, !inHazardBlock(p));
            if (target == null) return ActionStatus.failed("no safe ground within " + RADIUS + " blocks");
        }
        boolean burningNeedsWater = p.isOnFire() && target.water();
        if (!inHazardBlock(p) && !burningNeedsWater) {
            p.stopMoving();
            return ActionStatus.DONE;
        }
        double dx = target.pos().getX() + 0.5 - p.getX();
        double dz = target.pos().getZ() + 0.5 - p.getZ();
        double dy = target.pos().getY() - p.getY();
        if (Math.sqrt(dx * dx + dz * dz) < 0.3 && Math.abs(dy) < 1.0) {
            p.stopMoving();
            return ActionStatus.DONE;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.yBodyRot = yaw;
        p.xxa = 0.0F;
        p.zza = 1.0F;
        // Jumping swims up out of lava or water and hops a one-block ledge.
        p.setJumping(p.isInLava() || (p.isInWater() && dy > -0.5) || p.horizontalCollision || (p.onGround() && dy > 0.5));
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.player().stopMoving();
    }

    @Override
    public String describe() {
        return "escape lava or fire";
    }

    @Override
    public int timeoutTicks() {
        return 5 * 20;
    }

    // ---------------------------------------------------------------- finding safe ground

    private static Target find(AiPlayer p, boolean waterOnly) {
        ServerLevel level = p.level();
        BlockPos origin = p.blockPosition();
        Target best = null;
        double bestCost = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat > RADIUS) continue;
                for (int dy = -HEIGHT; dy <= HEIGHT; dy++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    boolean water = level.getFluidState(pos).is(FluidTags.WATER);
                    if (waterOnly && !water) continue;
                    double cost = flat + 0.5 * Math.abs(dy) - (water ? WATER_BONUS : 0);
                    if (cost >= bestCost || !safe(level, pos, water)) continue;
                    best = new Target(pos.immutable(), water);
                    bestCost = cost;
                }
            }
        }
        return best;
    }

    /** A cell the bot can stand or float in without touching lava, fire or magma. */
    private static boolean safe(ServerLevel level, BlockPos feet, boolean water) {
        BlockState at = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        if (dangerous(at) || dangerous(head) || !head.getCollisionShape(level, feet.above()).isEmpty()) return false;
        if (water) return true;
        if (!at.getCollisionShape(level, feet).isEmpty()) return false;
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        return floor.isFaceSturdy(level, below, Direction.UP) && !dangerous(floor);
    }
}
