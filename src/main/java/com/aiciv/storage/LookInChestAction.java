package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Opens a chest, reports everything in it and remembers it. */
public final class LookInChestAction implements Action {
    private final BlockPos given;
    private ContainerTarget target;
    private String doneSummary;

    /** @param orNull the chest to look in; null means the nearest one within 16 blocks */
    public LookInChestAction(BlockPos orNull) {
        this.given = orNull;
        this.target = new ContainerTarget(orNull);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        ContainerTarget.Step step = target.tick(ctx);
        switch (step.state()) {
            case RUNNING, OPENED:
                return ActionStatus.RUNNING;
            case FAILED:
                return ActionStatus.failed(step.reason());
            default:
        }
        Map<String, Integer> contents = target.contents();
        String where = "chest at " + target.coords();
        if (contents.isEmpty()) {
            ctx.event(where + " is empty");
        } else {
            List<Map.Entry<String, Integer>> kinds = new ArrayList<>(contents.entrySet());
            kinds.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : kinds) parts.add(e.getKey() + " x" + e.getValue());
            ctx.event(where + " holds: " + String.join(", ", parts));
        }
        target.remember(ctx);
        target.close(ctx);
        doneSummary = "looked in the " + where;
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        target.close(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        target.close(ctx);
        target = new ContainerTarget(given);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "look in a chest";
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
