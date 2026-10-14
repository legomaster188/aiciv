package com.aiciv.animals;

import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import java.util.function.Predicate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Walks over the items an interaction has just dropped (a lead, wool) and stops after a time limit. */
final class DropCollector {
    /** Slack for an item that was already a few ticks old when collecting began. */
    private static final int AGE_SLACK = 5;

    private final ItemGatherer gatherer;
    private final int limit;
    private int ticks;
    private boolean stopped;

    DropCollector(Predicate<ItemStack> match, double radius, int limit) {
        this.limit = limit;
        this.gatherer = new ItemGatherer(ctx -> {
            AiPlayer p = ctx.player();
            // Only what dropped since collecting began, so an item the bot dropped earlier is not chased.
            return p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && e.tickCount <= ticks + AGE_SLACK && match.test(e.getItem()));
        });
    }

    /** True while there is still something to walk to and time left. */
    boolean tick(BotContext ctx) {
        if (stopped) return false;
        boolean more = ++ticks <= limit && gatherer.tick(ctx);
        if (!more) stop(ctx);
        return more;
    }

    void stop(BotContext ctx) {
        if (stopped) return;
        stopped = true;
        gatherer.stop(ctx);
    }
}
