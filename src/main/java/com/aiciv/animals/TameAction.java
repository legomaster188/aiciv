package com.aiciv.animals;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.EntityUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.Riding;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.ItemStack;

/**
 * Tames the nearest untamed grown animal of one kind. Wolves, cats and parrots are fed their item until they accept
 * it; horses and their kin are ridden until they stop bucking, after a feed or two of something they like.
 */
public final class TameAction implements Action {
    static final double REACH = 2.5;
    static final int FEED_GAP_TICKS = 20;
    static final int MAX_ITEMS = 16;
    static final int MAX_RIDES = 8;
    static final int RIDE_TICKS = 200;
    static final int MAX_PRE_FEEDS = 2;
    /** Horse foods that raise temper, and so shorten the ride; a held one is used before the first ride. */
    private static final List<String> PRE_FEED = List.of("golden_carrot", "golden_apple", "apple", "sugar", "wheat");

    private final String label;
    private String kind;
    private Tameables.Method method;
    private Entity target;
    private Approach approach;
    private int used;
    private int cooldown;
    private int preFed;
    private int rides;
    private int ridingTicks;
    private int refusals;
    private String itemUsed = "";
    private String result;

    public TameAction(String animal) {
        this.label = animal;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (target == null) {
            String failure = choose(p);
            if (failure != null) return ActionStatus.failed(failure);
        }
        if (!target.isAlive()) return ActionStatus.failed("the " + kind + " is gone");
        return method == Tameables.Method.FEED ? feed(ctx, p) : ride(ctx, p);
    }

    private String choose(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        if (type == null) return "no animal called " + label;
        kind = Animals.id(type);
        method = Tameables.method(kind).orElse(null);
        if (method == null) return "a " + kind + " cannot be tamed (try wolf, cat, parrot, horse, donkey, mule, llama)";
        if (method == Tameables.Method.FEED) {
            List<String> items = Tameables.items(kind);
            if (InventoryOps.count(p, s -> items.contains(Animals.itemId(s))) == 0) {
                return Tameables.plural(kind) + " are tamed with " + String.join(", ", items) + " (you have none)";
            }
            target = Animals.nearest(p, type, TamableAnimal.class, Animals.RADIUS, a -> !a.isTame() && !a.isBaby()).orElse(null);
        } else {
            target = Animals.nearest(p, type, AbstractHorse.class, Animals.RADIUS, a -> !a.isTamed() && !a.isBaby()).orElse(null);
        }
        return target == null ? "no untamed " + kind + " within 16 blocks" : null;
    }

    // ---------------------------------------------------------------- feeding

    private ActionStatus feed(BotContext ctx, AiPlayer p) {
        TamableAnimal animal = (TamableAnimal) target;
        if (animal.isTame()) {
            if (!animal.isOwnedBy(p)) return ActionStatus.failed("the " + kind + " was tamed by someone else");
            result = "tamed the " + kind + " (" + used + " " + itemUsed + ")";
            return ActionStatus.DONE;
        }
        List<String> items = Tameables.items(kind);
        if (used >= MAX_ITEMS) return ActionStatus.failed("used " + used + " " + itemUsed + " and the " + kind + " is still wild");
        Approach.Status s = approachTick(ctx, REACH);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (cooldown > 0) {
            cooldown--;
            return ActionStatus.RUNNING;
        }
        if (!InventoryOps.selectBest(p, st -> items.contains(Animals.itemId(st)) ? 1 : 0)) {
            return ActionStatus.failed("ran out of " + String.join(", ", items) + " and the " + kind + " is still wild");
        }
        ItemStack held = p.getMainHandItem();
        String id = Animals.itemId(held);
        int before = held.getCount();
        BlockUse.Result r = EntityUse.interact(p, animal, InteractionHand.MAIN_HAND);
        if (r == BlockUse.Result.USED || r == BlockUse.Result.NO_EFFECT) {
            int spent = before - (p.getMainHandItem().is(held.getItem()) ? p.getMainHandItem().getCount() : 0);
            if (spent > 0) {
                used += spent;
                itemUsed = id;
            }
            cooldown = FEED_GAP_TICKS;
        } else if (++refusals > 40) {
            return ActionStatus.failed("could not reach the " + kind);
        } else {
            approach = null;
        }
        return ActionStatus.RUNNING;
    }

    // ---------------------------------------------------------------- riding

    private ActionStatus ride(BotContext ctx, AiPlayer p) {
        AbstractHorse horse = (AbstractHorse) target;
        if (horse.isTamed()) {
            if (p.getVehicle() == horse) Riding.dismount(p);
            result = "tamed the " + kind + (rides == 0 ? "" : " after " + rides + (rides == 1 ? " ride" : " rides"));
            return ActionStatus.DONE;
        }
        if (p.getVehicle() == horse) {
            if (++ridingTicks > RIDE_TICKS) {
                Riding.dismount(p);
                return ActionStatus.failed("rode the " + kind + " for " + RIDE_TICKS + " ticks and it is still wild");
            }
            return ActionStatus.RUNNING;
        }
        if (p.getVehicle() != null) Riding.dismount(p); // some other mount
        if (rides >= MAX_RIDES) return ActionStatus.failed("the " + kind + " threw you off " + MAX_RIDES + " times");
        Approach.Status s = approachTick(ctx, REACH);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (rides == 0 && preFed < MAX_PRE_FEEDS && InventoryOps.selectBest(p, st -> PRE_FEED.contains(Animals.itemId(st)) && horse.isFood(st) ? 1 : 0)) {
            // Feeding raises the temper the vanilla way; the horse only has to accept, so a refusal still counts as a feed.
            EntityUse.interact(p, horse, InteractionHand.MAIN_HAND);
            preFed++;
            return ActionStatus.RUNNING;
        }
        BlockUse.Result r = Riding.mount(p, horse);
        if (r == BlockUse.Result.USED) {
            rides++;
            ridingTicks = 0;
        } else if (r == BlockUse.Result.NO_EFFECT) {
            if (++refusals > 10) return ActionStatus.failed("the " + kind + " cannot be mounted");
        } else {
            approach = null;
        }
        return ActionStatus.RUNNING;
    }

    private Approach.Status approachTick(BotContext ctx, double reach) {
        if (approach == null) approach = Approach.entity(target, reach);
        return approach.tick(ctx);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        AiPlayer p = ctx.player();
        if (p != null && p.getVehicle() != null && p.getVehicle() == target) Riding.dismount(p);
    }

    @Override
    public void interrupted(BotContext ctx) {
        approach = null;
    }

    @Override
    public String describe() {
        return result != null ? result : "tame " + label;
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }
}
