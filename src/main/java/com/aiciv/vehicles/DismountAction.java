package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Riding;

/** Lets go of the keys and gets off whatever the bot is riding. */
public final class DismountAction implements Action {
    private String result;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!p.isPassenger()) {
            result = "you were not riding";
            return ActionStatus.DONE;
        }
        Riding.dismount(p);
        result = "got off";
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return result != null ? result : "dismount";
    }

    @Override
    public int timeoutTicks() {
        return 20;
    }
}
