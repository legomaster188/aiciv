package com.aiciv.actions;

/** Stands still. Because a decision replaces the queue, a lone stop also cancels everything else. */
public final class StopAction implements Action {
    @Override
    public ActionStatus tick(BotContext ctx) {
        ctx.navigator().stop();
        return ActionStatus.DONE;
    }

    @Override public String describe() { return "stop"; }
    @Override public int timeoutTicks() { return 20; }
}
