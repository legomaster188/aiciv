package com.aiciv.reflex;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Runs a fixed distance directly away from whoever is hurting the bot. */
final class FleeAction implements Action {
    static final double DISTANCE = 12;
    static final double RADIUS = 2;

    private final Entity from;
    private boolean started;

    FleeAction(Entity from) {
        this.from = from;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            Vec3 here = ctx.position();
            double dx = here.x - from.getX(), dz = here.z - from.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.1) { // standing on top of it: any direction will do
                dx = 1;
                dz = 0;
                d = 1;
            }
            ctx.navigator().goTo(new ColumnGoal(here.x + dx / d * DISTANCE, here.z + dz / d * DISTANCE, RADIUS));
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
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "flee";
    }

    @Override
    public int timeoutTicks() {
        return 6 * 20;
    }
}
