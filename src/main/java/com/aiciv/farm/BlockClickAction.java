package com.aiciv.farm;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Shared loop of tilling and planting: pick the next visible target block, walk until it can be clicked, click it, and
 * stop after {@code count} successes or when no target is left. A target that fails is skipped, never retried.
 */
abstract class BlockClickAction implements Action {
    static final double STAND_RADIUS = 4.0;

    protected final int count;
    protected int succeeded;
    private final Set<BlockPos> skipped = new HashSet<>();
    private boolean started;
    private BlockPos target;
    private Approach approach;
    private String problem;
    private String result;

    BlockClickAction(int count) {
        this.count = count;
    }

    /** A reason this cannot start at all, else null; may also prepare state. */
    abstract String start(AiPlayer p);

    /** Candidate blocks, best first. */
    abstract List<BlockPos> candidates(AiPlayer p);

    /** Selects the right item and clicks {@code pos}; true when it worked. */
    abstract boolean click(AiPlayer p, BlockPos pos);

    /** The finished summary, e.g. "tilled 3". */
    abstract String summary(BotContext ctx);

    /** FAILED reason when nothing at all could be done. */
    abstract String nothingToDo();

    /** Limits the number of successes (planting stops when the seeds run out). */
    int limit(AiPlayer p) {
        return count;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            String reason = start(p);
            if (reason != null) return ActionStatus.failed(reason);
            started = true;
        }
        if (succeeded >= limit(p)) return finish(ctx);
        if (target == null) {
            for (BlockPos pos : candidates(p)) {
                if (!skipped.contains(pos)) {
                    target = pos;
                    break;
                }
            }
            if (target == null) {
                if (succeeded > 0) return finish(ctx);
                return ActionStatus.failed(problem != null && skipped.size() > 0 ? problem : nothingToDo());
            }
            approach = Approach.block(target, STAND_RADIUS);
        }
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                problem = s.reason();
                skipped.add(target);
                target = null;
                return ActionStatus.RUNNING;
            default:
                if (click(p, target)) succeeded++;
                else skipped.add(target);
                target = null;
                return ActionStatus.RUNNING;
        }
    }

    private ActionStatus finish(BotContext ctx) {
        result = summary(ctx);
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        target = null; // plan again from wherever the bot is
        approach = null;
    }

    /** The label while running, the summary once finished (the executor reports describe() when an action ends). */
    @Override
    public String describe() {
        return result != null ? result : label();
    }

    abstract String label();

    @Override
    public int timeoutTicks() {
        return Math.min(3600, 1200 + 60 * count);
    }
}
