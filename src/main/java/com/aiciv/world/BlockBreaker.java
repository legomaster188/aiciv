package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Breaks one block at a time with the real survival break speed, drops and tool wear. */
public final class BlockBreaker {
    public record Status(State state, String reason) {
        public enum State { RUNNING, DONE, FAILED }

        public static final Status RUNNING = new Status(State.RUNNING, "");
        public static final Status DONE = new Status(State.DONE, "");

        public static Status failed(String reason) {
            return new Status(State.FAILED, reason);
        }
    }

    private static final int SWING_INTERVAL = 5;

    private final AiPlayer player;
    private BlockPos target;
    private float progress;
    private int ticks;

    public BlockBreaker(AiPlayer player) {
        this.player = player;
    }

    public void start(BlockPos pos) {
        cancel();
        target = pos.immutable();
    }

    public boolean busy() {
        return target != null;
    }

    public void cancel() {
        if (target != null) player.level().destroyBlockProgress(player.getId(), target, -1);
        target = null;
        progress = 0;
        ticks = 0;
    }

    public Status tick() {
        if (target == null) return Status.DONE;
        ServerLevel level = (ServerLevel) player.level();
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            cancel();
            return Status.DONE;
        }
        if (state.getDestroySpeed(level, target) < 0) {
            String reason = "can't break " + state.getBlock().getDescriptionId();
            cancel();
            return Status.failed(reason);
        }
        if (new AABB(target).distanceToSqr(player.getEyePosition()) > square(player.blockInteractionRange())) {
            cancel();
            return Status.failed("out of reach");
        }
        ItemStack tool = InventoryOps.bestToolFor(player, state);
        if (!tool.isEmpty()) InventoryOps.selectBest(player, s -> s == tool ? 1 : 0);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));
        if (ticks++ % SWING_INTERVAL == 0) player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        progress += state.getDestroyProgress(player, level, target);
        if (progress < 1.0F) {
            level.destroyBlockProgress(player.getId(), target, (int) (progress * 10));
            return Status.RUNNING;
        }
        boolean broken = player.gameMode.destroyBlock(target);
        cancel(); // also clears the crack
        return broken ? Status.DONE : Status.failed("couldn't break " + state.getBlock().getDescriptionId());
    }

    private static double square(double v) {
        return v * v;
    }
}
