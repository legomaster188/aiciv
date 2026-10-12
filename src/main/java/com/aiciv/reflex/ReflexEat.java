package com.aiciv.reflex;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Eats the most filling safe food the bot carries, holding the use key until the meal is finished. */
public final class ReflexEat implements Action {
    private static final Set<Item> HARMFUL = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT); // the same foods EatAction avoids

    private boolean started;
    private int foodBefore;
    private int countBefore;
    private String ate;

    /** True when the bot carries something it can eat without harm. */
    public static boolean hasFood(AiPlayer p) {
        return InventoryOps.count(p, s -> nutrition(s) > 0) > 0;
    }

    static int nutrition(ItemStack s) {
        if (s.isEmpty() || HARMFUL.contains(s.getItem())) return 0;
        FoodProperties food = s.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            if (!InventoryOps.selectBest(p, s -> nutrition(s))) return ActionStatus.failed("nothing to eat");
            ItemStack held = p.getMainHandItem();
            foodBefore = p.getFoodData().getFoodLevel();
            countBefore = held.getCount();
            ate = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
            started = true;
            p.gameMode.useItem(p, p.level(), held, InteractionHand.MAIN_HAND);
            // Vanilla decides whether this can be eaten now; never force a meal it refused.
            return p.isUsingItem() ? ActionStatus.RUNNING : ActionStatus.failed("could not start eating " + ate);
        }
        if (p.isUsingItem()) return ActionStatus.RUNNING;
        boolean finished = p.getFoodData().getFoodLevel() > foodBefore || p.getMainHandItem().getCount() < countBefore;
        return finished ? ActionStatus.DONE : ActionStatus.failed("could not eat " + ate);
    }

    /** What was eaten, once the meal has started. */
    public String ate() {
        return ate;
    }

    @Override
    public void cancel(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p.isUsingItem()) p.stopUsingItem();
    }

    @Override
    public String describe() {
        return "eat";
    }

    @Override
    public int timeoutTicks() {
        return 10 * 20;
    }
}
