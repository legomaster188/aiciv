package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.PlayerNames;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Walks up to a player, looks at them and throws items to them the way a player drops items at someone. */
public final class GiveAction implements Action {
    static final double NEAR = 64, REACH = 3.0;
    /** A thrown item cannot be picked up for 40 ticks, then it has to land. */
    static final int PICKUP_TICKS = 80;

    private final String player;
    private final String item;
    private final int count;
    private ServerPlayer recipient;
    private Approach approach;
    private final List<ItemEntity> thrown = new ArrayList<>();
    private int threw;
    private int waited;
    private String id;
    private String doneSummary;

    public GiveAction(String player, String item, int count) {
        this.player = PlayerNames.clean(player);
        this.item = item;
        this.count = Math.max(1, count);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer me = ctx.player();
        if (recipient == null) {
            ServerPlayer found = me.level().getServer().getPlayerList().getPlayerByName(player);
            if (found == null || found == me || found.level() != me.level() || found.isSpectator() || !found.isAlive()
                    || me.distanceTo(found) > NEAR) {
                return ActionStatus.failed(player + " is not nearby");
            }
            if (InventoryOps.count(me, ItemNames.matcher(item)) < 1) return ActionStatus.failed("no " + item + " in inventory");
            recipient = found;
            approach = Approach.entity(found, REACH);
        }
        if (threw == 0) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
            throwItems(ctx, me);
            if (threw == 0) return ActionStatus.failed("no " + item + " in inventory");
            return ActionStatus.RUNNING;
        }
        thrown.removeIf(e -> e.isRemoved() && !e.isAlive());
        if (thrown.isEmpty()) {
            doneSummary = "gave " + threw + " " + id + " to " + player;
            return ActionStatus.DONE;
        }
        if (++waited >= PICKUP_TICKS) {
            doneSummary = "threw " + threw + " " + id + " to " + player + " (not picked up yet)";
            return ActionStatus.DONE;
        }
        return ActionStatus.RUNNING;
    }

    private void throwItems(BotContext ctx, AiPlayer me) {
        me.lookAt(EntityAnchorArgument.Anchor.EYES, recipient.getEyePosition());
        Predicate<ItemStack> match = ItemNames.matcher(item);
        for (ItemStack stack : me.getInventory().getNonEquipmentItems()) {
            if (threw >= count) break;
            if (stack.isEmpty() || !match.test(stack)) continue;
            if (id == null) id = ItemNames.id(stack);
            ItemStack piece = stack.split(Math.min(stack.getCount(), count - threw));
            threw += piece.getCount();
            ItemEntity entity = me.drop(piece, false, Prediction.SERVER_ONLY);
            if (entity != null) thrown.add(entity);
        }
        if (threw > 0) ctx.tell(player, me.getScoreboardName() + " threw you " + threw + " " + id);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (threw == 0 && recipient != null) approach = Approach.entity(recipient, REACH);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "give " + count + " " + item + " to " + player;
    }

    @Override
    public int timeoutTicks() {
        return 120 * 20;
    }
}
