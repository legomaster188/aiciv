package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.dimension.Portals;

/** A stage that only fails, so a routine can refuse with a reason from where it chooses its stages. */
final class Refusal implements Action {
    private final String what;
    private final String reason;

    Refusal(String what, String reason) {
        this.what = what;
        this.reason = reason;
    }

    /** {@code "<tool> works in the_nether; you are in overworld"}, or null when the bot is in the nether. */
    static String notInNether(BotContext ctx, String tool) {
        String here = Portals.shortName(ctx.dimension());
        return here.equals("the_nether") ? null : tool + " works in the_nether; you are in " + here;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        return ActionStatus.failed(reason);
    }

    @Override
    public String describe() {
        return what;
    }

    @Override
    public int timeoutTicks() {
        return 1;
    }
}
