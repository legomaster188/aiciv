package com.aiciv.actions;

/** One long-running thing a bot does. Ticked on the server thread until it is done or failed. */
public interface Action {
    ActionStatus tick(BotContext ctx);

    String describe();

    int timeoutTicks();

    /** Called when the action is replaced, stopped or timed out. */
    default void cancel(BotContext ctx) {}
}
