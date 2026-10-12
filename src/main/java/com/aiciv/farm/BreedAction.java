package com.aiciv.farm;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.EntityUse;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;

/** Feeds two grown animals of one kind the food they like, so they fall in love and breed. */
public final class BreedAction implements Action {
    static final double RADIUS = 16;
    static final double FEED_RADIUS = 3.0;
    /** Ticks a feeding may keep being refused (out of reach or sight) before the action gives up. */
    static final int MAX_REFUSALS = 20;

    private final String label;
    private List<Animal> pair;
    private int fed;
    private Approach approach;
    private int refusals;
    private String kind;
    private String result;

    public BreedAction(String animal) {
        this.label = animal;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (pair == null) {
            ActionStatus failure = choose(p);
            if (failure != null) return failure;
        }
        if (pair.stream().allMatch(a -> a.isAlive() && a.isInLove())) {
            result = "bred two " + kind + "s";
            return ActionStatus.DONE;
        }
        Animal target = pair.get(fed);
        if (!target.isAlive()) return ActionStatus.failed("the " + kind + " is gone");
        if (target.isInLove()) { // already fed
            fed = Math.min(fed + 1, pair.size() - 1);
            approach = null;
            return ActionStatus.RUNNING;
        }
        if (approach == null) approach = Approach.entity(target, FEED_RADIUS);
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (!InventoryOps.selectBest(p, st -> target.isFood(st) ? 1 : 0)) return ActionStatus.failed("ran out of food for " + kind + "s");
        if (EntityUse.interact(p, target, InteractionHand.MAIN_HAND) == BlockUse.Result.USED) {
            refusals = 0;
            if (fed < pair.size() - 1) fed++;
            approach = null;
        } else if (++refusals > MAX_REFUSALS) {
            return ActionStatus.failed("could not feed the " + kind);
        } else {
            approach = null; // walk up again
        }
        return ActionStatus.RUNNING;
    }

    /** Picks the two nearest grown, willing animals and checks the bot has food for them. Null when all is well. */
    private ActionStatus choose(AiPlayer p) {
        EntityType<?> type = AttackAction.entityType(label);
        kind = type == null ? label : BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        if (type == null) return ActionStatus.failed("no animal called " + label);
        List<Animal> candidates = new ArrayList<>(p.level().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(RADIUS),
                a -> a.getType() == type && a.isAlive() && !a.isBaby() && a.canFallInLove() && !a.isInLove() && a.distanceTo(p) <= RADIUS));
        if (candidates.size() < 2) return ActionStatus.failed("need two grown " + kind + "s nearby");
        candidates.sort(Comparator.comparingDouble(a -> a.distanceToSqr(p)));
        Animal first = candidates.get(0);
        int food = InventoryOps.count(p, first::isFood);
        if (food < 2) {
            return ActionStatus.failed("needs 2 food for " + kind + "s (they eat " + String.join(", ", AnimalFood.foods(kind)) + ")");
        }
        pair = List.of(first, candidates.get(1));
        return null;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        approach = null;
    }

    @Override
    public String describe() {
        return result != null ? result : "breed " + label;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
