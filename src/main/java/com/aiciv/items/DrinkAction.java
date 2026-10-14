package com.aiciv.items;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** Drinks a held potion by name, or milk or honey, and waits for the animation to finish. */
public final class DrinkAction implements Action {
    private final String item;
    private final String name;
    private Predicate<ItemStack> match;
    private int before;
    private boolean started;
    private String done;

    public DrinkAction(String item) {
        this.item = item;
        this.name = Potions.normalise(item).orElse(item.trim());
    }

    /** The registry name of a potion item's potion ("swiftness", "strong_healing"), else null. */
    static String potionKey(ItemStack s) {
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null) return null;
        return c.potion().flatMap(Holder::unwrapKey).map(k -> k.identifier().getPath()).orElse(null);
    }

    static boolean isPotionOf(ItemStack s, net.minecraft.world.item.Item type, String potion) {
        return s.is(type) && (potion == null || Potions.sameKind(potionKey(s), potion));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            match = switch (name) {
                case "milk_bucket" -> s -> s.is(Items.MILK_BUCKET);
                case "honey_bottle" -> s -> s.is(Items.HONEY_BOTTLE);
                default -> s -> isPotionOf(s, Items.POTION, name);
            };
            if (InventoryOps.count(p, match) == 0) return ActionStatus.failed("no " + name + " to drink");
            before = InventoryOps.count(p, match);
            started = true;
        }
        if (!p.isUsingItem()) {
            if (InventoryOps.count(p, match) < before) {
                done = "drank " + name;
                return ActionStatus.DONE;
            }
            if (!ItemUse.select(p, match)) return ActionStatus.failed("no " + name + " to drink");
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (!p.isUsingItem()) return ActionStatus.failed("could not start drinking " + name);
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p != null && p.isUsingItem()) p.stopUsingItem();
    }

    @Override
    public String describe() {
        return done != null ? done : "drink " + item;
    }

    @Override
    public int timeoutTicks() {
        return 80;
    }
}
