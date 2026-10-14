package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;

/** Walks to within a radius of a map column (x, z) at any height: one leg of a long trip. */
final class WalkColumnAction implements Action {
    private final double x, z, radius;
    private final String what;
    private boolean started;

    WalkColumnAction(double x, double z, double radius, String what) {
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.what = what;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            ctx.navigator().goTo(new ColumnGoal(x, z, radius));
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        return switch (s.state()) {
            case RUNNING -> ActionStatus.RUNNING;
            case ARRIVED -> ActionStatus.DONE;
            case FAILED -> ActionStatus.failed(s.reason());
            case IDLE -> ActionStatus.failed("navigation stopped");
        };
    }

    @Override
    public void interrupted(BotContext ctx) {
        started = false; // walk on toward the same column
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "walk toward " + what + " at " + Math.round(x) + " " + Math.round(z);
    }

    @Override
    public int timeoutTicks() {
        return 3600;
    }
}
