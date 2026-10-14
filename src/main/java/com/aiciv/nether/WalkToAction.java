package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import net.minecraft.core.BlockPos;

/** Walks to within a radius of a block; a stage of the nether routines. */
final class WalkToAction implements Action {
    private static final int MAX_NAV_FAILURES = 3;

    private final BlockPos target;
    private final double radius;
    private boolean started;
    private int failures;

    WalkToAction(BlockPos target, double radius) {
        this.target = target;
        this.radius = radius;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            go(ctx);
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        return switch (nav.state()) {
            case ARRIVED -> ActionStatus.DONE;
            case RUNNING -> ActionStatus.RUNNING;
            case FAILED -> {
                if (++failures >= MAX_NAV_FAILURES) yield ActionStatus.failed(nav.reason());
                go(ctx);
                yield ActionStatus.RUNNING;
            }
            case IDLE -> {
                go(ctx);
                yield ActionStatus.RUNNING;
            }
        };
    }

    private void go(BotContext ctx) {
        ctx.navigator().goTo(new NearGoal(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, radius));
    }

    @Override
    public void interrupted(BotContext ctx) {
        started = false;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "walk to " + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    @Override
    public int timeoutTicks() {
        return 3600;
    }
}
