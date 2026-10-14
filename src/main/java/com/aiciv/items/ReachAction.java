package com.aiciv.items;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * An action on one block: {@link #prepare} picks the block (or refuses), the bot walks until it can see it within
 * reach, then {@link #arrived} runs every tick until it says it is done.
 */
abstract class ReachAction implements Action {
    static final double REACH = 4.0;

    /** Set by {@link #prepare}. */
    protected BlockPos target;
    protected String done;
    private Approach approach;
    private boolean there;
    private double radius = REACH;

    /** A reason to refuse before moving, else null after choosing {@link #target}. */
    protected abstract String prepare(AiPlayer p);

    /** Runs each tick once the bot is in position. */
    protected abstract ActionStatus arrived(BotContext ctx);

    protected abstract String label();

    /** Walk closer (to {@code newRadius}) and look again; returns RUNNING for {@link #arrived} to pass on. */
    protected final ActionStatus retryCloser(double newRadius) {
        radius = newRadius;
        approach = Approach.block(target, radius);
        there = false;
        return ActionStatus.RUNNING;
    }

    @Override
    public final ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (approach == null) {
            String reason = prepare(p);
            if (reason != null) return ActionStatus.failed(reason);
            approach = Approach.block(target, radius);
        }
        if (!there) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
            there = true;
        }
        return arrived(ctx);
    }

    protected final String blockId(AiPlayer p, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(pos).getBlock()).getPath();
    }

    protected final String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (target != null && approach != null) {
            approach = Approach.block(target, radius);
            there = false;
        }
    }

    @Override
    public String describe() {
        return done != null ? done : label();
    }
}
