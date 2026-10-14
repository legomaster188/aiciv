package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;

/** A stage that fails at once, so a routine can refuse to start with a readable reason. */
final class Fail implements Action {
    private final String what;
    private final String reason;

    Fail(String what, String reason) {
        this.what = what;
        this.reason = reason;
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
