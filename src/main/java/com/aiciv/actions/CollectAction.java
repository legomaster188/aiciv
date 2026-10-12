package com.aiciv.actions;

import com.aiciv.world.BlockNames;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;

/** Picks up matching items lying on the ground within 16 blocks. */
public final class CollectAction implements Action {
    static final int RADIUS = 16;

    private final String query;
    private final ItemGatherer gatherer;
    private boolean sawAny;

    public CollectAction(String itemQuery) {
        this.query = BlockNames.normalise(itemQuery);
        Predicate<String> matcher = BlockNames.matcher(itemQuery);
        this.gatherer = new ItemGatherer(ctx -> nearbyItems(ctx, matcher));
    }

    private static List<ItemEntity> nearbyItems(BotContext ctx, Predicate<String> matcher) {
        var player = ctx.player();
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(RADIUS), item ->
                item.distanceTo(player) <= RADIUS
                        && matcher.test(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath()));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (gatherer.tick(ctx)) {
            sawAny = true;
            return ActionStatus.RUNNING;
        }
        gatherer.stop(ctx);
        if (!sawAny) return ActionStatus.failed("no " + query + " lying within " + RADIUS + " blocks");
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        gatherer.stop(ctx);
    }

    @Override
    public String describe() {
        return "collect " + query;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
