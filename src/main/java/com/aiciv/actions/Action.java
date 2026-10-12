package com.aiciv.actions;

/** One long-running thing a bot does. Ticked on the server thread until it is done or failed. */
public interface Action {
    ActionStatus tick(BotContext ctx);

    String describe();

    int timeoutTicks();

    /** Called when the action is replaced, stopped or timed out. */
    default void cancel(BotContext ctx) {}

    /**
     * Called when a reflex takes over the bot. The action is not ticked again until the reflex ends, and the
     * reflex may have moved the bot and stopped its navigation, so forget any navigation or dig in progress and
     * plan again from wherever the bot is on the next tick.
     */
    default void interrupted(BotContext ctx) {}
}
