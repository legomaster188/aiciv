package com.aiciv.actions;

import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class FollowAction implements Action {
    static final double CLOSE = 3.0;
    private final String name;
    private final int seconds;
    private int ticks;
    private Vec3 anchor;

    public FollowAction(String name, int seconds) {
        this.name = name;
        this.seconds = Math.max(1, Math.min(300, seconds));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (ticks++ >= seconds * 20) {
            ctx.navigator().stop();
            return ActionStatus.DONE;
        }
        Optional<Vec3> target = ctx.playerPosition(name);
        if (target.isEmpty()) {
            ctx.navigator().stop();
            return ActionStatus.failed(name + " is not online in this dimension");
        }
        Vec3 t = target.get();
        if (ctx.position().distanceTo(t) <= CLOSE) {
            if (anchor != null) {
                ctx.navigator().stop();
                anchor = null;
            }
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        if (anchor != null && s.state() == NavStatus.State.FAILED) {
            return ActionStatus.failed("can't reach " + name + ": " + s.reason());
        }
        // An arrived navigator stays put until the player moves away from the anchor, so it is not re-issued each tick.
        if (anchor == null || anchor.distanceTo(t) > CLOSE || s.state() == NavStatus.State.IDLE) {
            anchor = t;
            ctx.navigator().goTo(new NearGoal(t.x, t.y, t.z, CLOSE - 0.5));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "follow " + name + " " + seconds + "s";
    }

    @Override
    public int timeoutTicks() {
        return (seconds + 5) * 20;
    }
}
