package com.aiciv.animals;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Milks the nearest grown cow, goat or mooshroom into a bucket (a mooshroom also fills a bowl with stew). */
public final class MilkAction extends InteractAction {
    static final List<String> MILKABLE = List.of("cow", "goat", "mooshroom");

    private Item container;
    private Item product;
    private int before;

    public MilkAction(String animalOrNull) {
        super(animalOrNull == null || animalOrNull.isBlank() ? "cow" : animalOrNull.trim());
    }

    @Override
    protected String find(AiPlayer p) {
        EntityType<?> type = Animals.type(label);
        String kind = type == null ? label : Animals.id(type);
        if (type == null || !MILKABLE.contains(kind)) return "cannot milk a " + label + " (milk " + String.join(", ", MILKABLE) + ")";
        if (InventoryOps.has(p, Items.BUCKET, 1)) {
            container = Items.BUCKET;
            product = Items.MILK_BUCKET;
        } else if (kind.equals("mooshroom") && InventoryOps.has(p, Items.BOWL, 1)) {
            container = Items.BOWL;
            product = Items.MUSHROOM_STEW;
        } else {
            return kind.equals("mooshroom") ? "needs an empty bucket or a bowl (you have neither)" : "needs an empty bucket (you have none)";
        }
        target = Animals.nearest(p, type, LivingEntity.class, Animals.RADIUS, e -> !e.isBaby()).orElse(null);
        if (target == null) return "no grown " + kind + " within 16 blocks";
        before = InventoryOps.count(p, s -> s.is(product));
        return null;
    }

    @Override
    protected boolean hold(AiPlayer p) {
        return InventoryOps.selectBest(p, s -> s.is(container) ? 1 : 0);
    }

    @Override
    protected String missing() {
        return "ran out of " + (container == Items.BOWL ? "bowls" : "empty buckets");
    }

    @Override
    protected boolean done(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(product)) > before;
    }

    @Override
    protected String success(AiPlayer p) {
        return "milked the " + Animals.id(target.getType());
    }

    @Override
    protected String verb() {
        return "milk";
    }
}
