package com.aiciv.actions;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import net.minecraft.world.phys.Vec3;

public final class GoToAction implements Action {
    static final double LEG_LENGTH = 128, LEG_STEP = 120, LEG_RADIUS = 8, ARRIVE_RADIUS = 1.5;
    private final int x, y, z;
    private boolean started;
    private boolean finalLeg;

    public GoToAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            startLeg(ctx);
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        return switch (s.state()) {
            case RUNNING -> ActionStatus.RUNNING;
            case FAILED -> ActionStatus.failed(s.reason());
            case IDLE -> ActionStatus.failed("navigation stopped");
            case ARRIVED -> {
                if (finalLeg) yield ActionStatus.DONE;
                startLeg(ctx);
                yield ActionStatus.RUNNING;
            }
        };
    }

    private void startLeg(BotContext ctx) {
        Vec3 p = ctx.position();
        double tx = x + 0.5, tz = z + 0.5;
        double dx = tx - p.x, dz = tz - p.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > LEG_LENGTH) {
            finalLeg = false;
            ctx.navigator().goTo(new ColumnGoal(p.x + dx / dist * LEG_STEP, p.z + dz / dist * LEG_STEP, LEG_RADIUS));
        } else {
            finalLeg = true;
            ctx.navigator().goTo(new NearGoal(tx, y, tz, ARRIVE_RADIUS));
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "goTo " + x + " " + y + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 2 * 60 * 20;
    }
}
