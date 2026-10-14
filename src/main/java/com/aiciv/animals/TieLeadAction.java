package com.aiciv.animals;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Sight;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;

/** Ties every animal the bot is leading to a fence: a click on the fence binds them, as in vanilla. */
public final class TieLeadAction implements Action {
    static final int FENCE_RADIUS = 8;
    static final double REACH = 4.0;
    static final int MAX_CLICKS = 5;
    /** Ticks the led animals get to catch up after a click before it is tried again. */
    static final int SETTLE_TICKS = 15;

    private final BlockPos given;
    private List<Leashable> led;
    private BlockPos fence;
    private Approach approach;
    private int clicks;
    private int settling;
    private String result;

    public TieLeadAction(BlockPos fenceOrNull) {
        this.given = fenceOrNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (led == null) {
            led = Leashable.leashableLeashedTo(p);
            if (led.isEmpty()) return ActionStatus.failed("you are not leading any animal");
            String failure = chooseFence(p);
            if (failure != null) return ActionStatus.failed(failure);
        }
        if (led.stream().allMatch(l -> !l.isLeashed() || l.getLeashHolder() instanceof LeashFenceKnotEntity)) {
            result = "tied " + led.size() + " animal" + (led.size() == 1 ? "" : "s") + " to the fence";
            return ActionStatus.DONE;
        }
        if (settling > 0) {
            settling--;
            return ActionStatus.RUNNING;
        }
        if (approach == null) approach = Approach.block(fence, REACH);
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (clicks >= MAX_CLICKS) {
            long left = led.stream().filter(l -> l.isLeashed() && !(l.getLeashHolder() instanceof LeashFenceKnotEntity)).count();
            return ActionStatus.failed(left + " of " + led.size() + " animals are still on your lead (they were too far from the fence)");
        }
        BlockUse.withFreeHand(p);
        BlockUse.Result r = BlockUse.click(p, fence);
        if (r == BlockUse.Result.USED || r == BlockUse.Result.NO_EFFECT) {
            clicks++;
            settling = SETTLE_TICKS;
        }
        return ActionStatus.RUNNING;
    }

    private String chooseFence(AiPlayer p) {
        if (given != null) {
            if (!p.level().getBlockState(given).is(BlockTags.FENCES)) {
                return "there is no fence at " + given.getX() + " " + given.getY() + " " + given.getZ();
            }
            fence = given;
            return null;
        }
        BlockPos here = p.blockPosition();
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-FENCE_RADIUS, -FENCE_RADIUS, -FENCE_RADIUS), here.offset(FENCE_RADIUS, FENCE_RADIUS, FENCE_RADIUS))) {
            if (!p.level().getBlockState(pos).is(BlockTags.FENCES)) continue;
            double d = pos.distSqr(here);
            if (d < best && d <= FENCE_RADIUS * FENCE_RADIUS && Sight.canSee(p, pos)) {
                best = d;
                fence = pos.immutable();
            }
        }
        return fence == null ? "no fence in sight within 8 blocks" : null;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        approach = null;
    }

    @Override
    public String describe() {
        return result != null ? result : "tie the lead to a fence";
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
