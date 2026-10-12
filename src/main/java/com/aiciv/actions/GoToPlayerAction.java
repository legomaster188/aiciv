package com.aiciv.actions;

import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class GoToPlayerAction implements Action {
    static final double ARRIVE = 3.0;
    private final String name;
    private Vec3 anchor;

    public GoToPlayerAction(String name) {
        this.name = name;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        Optional<Vec3> target = ctx.playerPosition(name);
        if (target.isEmpty()) return ActionStatus.failed(name + " is not online in this dimension");
        Vec3 t = target.get();
        if (ctx.position().distanceTo(t) <= ARRIVE) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        NavStatus s = ctx.navigator().status();
        if (anchor != null && s.state() == NavStatus.State.FAILED) {
            return ActionStatus.failed("can't reach " + name + ": " + s.reason());
        }
        if (anchor != null && s.state() == NavStatus.State.ARRIVED && anchor.distanceTo(t) <= ARRIVE) {
            // The goal sits at the block centre, so the bot can arrive a little outside the 3-block margin.
            // The player has not moved, so re-issuing the same goal would only arrive again until the timeout.
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        if (anchor == null || anchor.distanceTo(t) > ARRIVE || s.state() != NavStatus.State.RUNNING) {
            anchor = t;
            ctx.navigator().goTo(new NearGoal(t.x, t.y, t.z, ARRIVE - 0.5));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "goToPlayer " + name;
    }

    @Override
    public int timeoutTicks() {
        return 2 * 60 * 20;
    }
}
