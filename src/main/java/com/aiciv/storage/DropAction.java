package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import java.util.function.Predicate;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/** Throws items forward from the inventory, as a player pressing Q. */
public final class DropAction implements Action {
    private final String item;
    private final int count;
    private String doneSummary;

    public DropAction(String item, int count) {
        this.item = item;
        this.count = Math.max(1, count);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        Predicate<ItemStack> match = ItemNames.matcher(item);
        if (InventoryOps.count(p, match) < 1) return ActionStatus.failed("no " + item + " in inventory");
        int dropped = 0;
        String id = null;
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (dropped >= count) break;
            if (stack.isEmpty() || !match.test(stack)) continue;
            if (id == null) id = ItemNames.id(stack);
            ItemStack piece = stack.split(Math.min(stack.getCount(), count - dropped));
            dropped += piece.getCount();
            p.drop(piece, false, Prediction.SERVER_ONLY);
        }
        doneSummary = "dropped " + dropped + " " + id;
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "drop " + count + " " + item;
    }

    @Override
    public int timeoutTicks() {
        return 5 * 20;
    }
}
