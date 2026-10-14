package com.aiciv.dragon;

import com.aiciv.actions.BotContext;
import com.aiciv.dimension.Portals;

/** The dimension rule for the actions of this package: they all work in the End. */
final class TheEnd {
    private static final String KEY = "minecraft:the_end";

    private TheEnd() {}

    /** Null when the bot is in the End (or the action was built for any dimension, as in game tests). */
    static String problem(BotContext ctx, String tool, boolean anyDimension) {
        if (anyDimension) return null;
        String now = ctx.dimension();
        if (now.equals(KEY)) return null;
        return tool + " works in " + Portals.shortName(KEY) + "; you are in " + Portals.shortName(now);
    }
}
