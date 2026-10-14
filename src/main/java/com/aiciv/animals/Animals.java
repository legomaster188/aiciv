package com.aiciv.animals;

import com.aiciv.actions.AttackAction;
import com.aiciv.bot.AiPlayer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

/** Small shared lookups for the animal actions. */
final class Animals {
    static final double RADIUS = 16;

    private Animals() {}

    /** The entity type a model's word means ("wolves", "Horse"), or null. */
    static EntityType<?> type(String label) {
        return label == null ? null : AttackAction.entityType(label);
    }

    /** The registry id without namespace: "wolf", "trader_llama". */
    static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
    }

    static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** Living, loaded entities of {@code type} within {@code radius} that are instances of {@code cls} and pass {@code filter}, nearest first. */
    static <T extends Entity> List<T> nearby(AiPlayer p, EntityType<?> type, Class<T> cls, double radius, Predicate<T> filter) {
        return p.level().getEntitiesOfClass(cls, p.getBoundingBox().inflate(radius),
                        e -> e.getType() == type && e.isAlive() && e.distanceTo(p) <= radius && filter.test(e))
                .stream().sorted(Comparator.comparingDouble(e -> e.distanceToSqr(p))).toList();
    }

    static <T extends Entity> Optional<T> nearest(AiPlayer p, EntityType<?> type, Class<T> cls, double radius, Predicate<T> filter) {
        return nearby(p, type, cls, radius, filter).stream().findFirst();
    }
}
