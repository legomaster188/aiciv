package com.aiciv.reflex;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;

/** Swims straight up until the bot's head is out of the water. */
final class SurfaceAction implements Action {
    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!p.isUnderWater()) {
            p.stopMoving();
            return ActionStatus.DONE;
        }
        p.xxa = 0.0F;
        p.zza = 0.0F;
        p.setJumping(true);
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.player().stopMoving();
    }

    @Override
    public String describe() {
        return "swim up";
    }

    @Override
    public int timeoutTicks() {
        return 10 * 20;
    }
}
