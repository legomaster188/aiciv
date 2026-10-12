package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Takes up to {@code count} of an item out of a chest; reports what it actually got. */
public final class WithdrawAction implements Action {
    private final String item;
    private final int count;
    private final BlockPos given;
    private ContainerTarget target;
    private String doneSummary;

    /** @param orNull the chest to use; null means the nearest one within 16 blocks */
    public WithdrawAction(String item, int count, BlockPos orNull) {
        this.item = item;
        this.count = Math.max(1, count);
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
        AiPlayer p = ctx.player();
        Predicate<ItemStack> match = ItemNames.matcher(item);
        List<Slot> slots = new ArrayList<>();
        int available = 0;
        String id = null;
        for (Slot s : MenuOps.containerSlots(target.menu())) {
            ItemStack stack = s.getItem();
            if (stack.isEmpty() || !match.test(stack)) continue;
            slots.add(s);
            available += stack.getCount();
            if (id == null) id = ItemNames.id(stack);
        }
        if (slots.isEmpty()) {
            target.remember(ctx);
            target.close(ctx);
            return ActionStatus.failed("no " + item + " in the chest");
        }
        int wanted = Math.min(count, available);
        int taken = 0;
        for (Slot s : slots) {
            if (taken >= wanted) break;
            taken += MenuOps.moveOut(p, target.menu(), target.menu().slots.indexOf(s), wanted - taken);
        }
        target.remember(ctx);
        target.close(ctx);
        if (taken == 0) return ActionStatus.failed("your inventory is full");
        if (taken >= count) {
            doneSummary = "took " + taken + " " + id;
        } else if (taken < available) {
            doneSummary = "took " + taken + " of " + count + " " + id + " (inventory full)";
        } else {
            doneSummary = "took " + taken + " of " + count + " " + id + " (the chest had only " + available + ")";
        }
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
        return doneSummary != null ? doneSummary : "withdraw " + count + " " + item;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
