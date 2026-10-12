package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Eats the most filling safe food it holds and waits for the eating animation to finish. */
public final class EatAction implements Action {
    /** Foods with a harmful or disruptive effect; a bot never eats these on purpose. */
    private static final Set<String> AVOID = Set.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "suspicious_stew", "chorus_fruit");

    private Item food;
    private int before;
    private boolean started;
    private String eaten = "";

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer player = ctx.player();
        if (!started) {
            if (player.getFoodData().getFoodLevel() >= 20) return ActionStatus.failed("not hungry");
            ItemStack best = best(player);
            if (best == null) return ActionStatus.failed("nothing to eat");
            food = best.getItem();
            eaten = BuiltInRegistries.ITEM.getKey(food).getPath();
            before = InventoryOps.count(player, s -> s.is(food));
            InventoryOps.selectBest(player, s -> s.is(food) ? 1 : 0);
            ItemStack inHand = player.getItemInHand(InteractionHand.MAIN_HAND);
            player.gameMode.useItem(player, player.level(), inHand, InteractionHand.MAIN_HAND);
            started = true;
            if (!player.isUsingItem()) return ActionStatus.failed("could not start eating " + eaten);
            return ActionStatus.RUNNING;
        }
        if (player.isUsingItem()) return ActionStatus.RUNNING;
        int now = InventoryOps.count(player, s -> s.is(food));
        if (now < before) return ActionStatus.DONE;
        return ActionStatus.failed("stopped eating " + eaten + " before finishing");
    }

    private static ItemStack best(AiPlayer player) {
        ItemStack best = null;
        int bestNutrition = -1;
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) continue;
            FoodProperties props = s.get(DataComponents.FOOD);
            if (props == null || AVOID.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) continue;
            if (props.nutrition() > bestNutrition) {
                bestNutrition = props.nutrition();
                best = s;
            }
        }
        return best;
    }

    @Override
    public void cancel(BotContext ctx) {
        AiPlayer player = ctx.player();
        if (player != null && player.isUsingItem()) player.stopUsingItem();
    }

    @Override
    public String describe() {
        return eaten.isEmpty() ? "eat" : "eat " + eaten;
    }

    @Override
    public int timeoutTicks() {
        return 10 * 20;
    }
}
