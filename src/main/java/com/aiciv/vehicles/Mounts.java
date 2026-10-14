package com.aiciv.vehicles;

import com.aiciv.actions.AttackAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Items;

/** Finding things to ride and knowing whether a bot could steer them. */
public final class Mounts {
    static final Set<String> KINDS = Set.of("boat", "minecart", "horse", "donkey", "mule", "llama", "camel", "pig", "strider");
    static final double RANGE = 16;

    private Mounts() {}

    /** The nearest free {@code kind} (boat, minecart or an animal) within {@code radius}. */
    public static Optional<Entity> find(AiPlayer p, String kind, double radius) {
        String k = kind.trim().toLowerCase(Locale.ROOT);
        EntityType<?> type = KINDS.contains(k) && !k.equals("boat") && !k.equals("minecart") ? AttackAction.entityType(k) : null;
        return p.level().getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(radius), e -> {
                    if (e == p || !e.isAlive() || e.isVehicle() || e.distanceTo(p) > radius) return false;
                    if (k.equals("boat")) return e instanceof AbstractBoat;
                    if (k.equals("minecart")) return e instanceof AbstractMinecart;
                    return type != null && e.getType() == type;
                }).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
    }

    /** What the vehicle is called to a model: boat, minecart, or the animal's id. */
    public static String kindOf(Entity v) {
        if (v instanceof AbstractBoat) return "boat";
        if (v instanceof AbstractMinecart) return "minecart";
        return idOf(v);
    }

    /** The entity's own id: oak_boat, minecart, horse. */
    public static String idOf(Entity v) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
    }

    /** Null when the bot could steer {@code v}; otherwise why not. */
    public static String problem(AiPlayer p, Entity v) {
        String kind = idOf(v);
        if (v instanceof Llama) return "llamas cannot be ridden";
        if (v instanceof AbstractHorse horse) {
            if (!horse.isTamed()) return "the " + kind + " is not tamed (use tame)";
            if (!horse.isSaddled()) return "the " + kind + " needs a saddle (use saddle)";
            return null;
        }
        if (v instanceof Pig pig) {
            if (!pig.isSaddled()) return "the pig needs a saddle (use saddle)";
            if (!InventoryOps.has(p, Items.CARROT_ON_A_STICK, 1)) return "steer a pig with a carrot_on_a_stick";
            return null;
        }
        if (v instanceof Strider strider) {
            if (!strider.isSaddled()) return "the strider needs a saddle (use saddle)";
            if (!InventoryOps.has(p, Items.WARPED_FUNGUS_ON_A_STICK, 1)) return "steer a strider with a warped_fungus_on_a_stick";
            return null;
        }
        return v instanceof Mob ? "the " + kind + " cannot be steered" : null;
    }

    /** A pig or strider only obeys while its rider holds the matching stick, so take it in hand. */
    static void holdSteeringItem(AiPlayer p, Entity v) {
        if (v instanceof Pig) ItemUse.select(p, st -> st.is(Items.CARROT_ON_A_STICK));
        else if (v instanceof Strider) ItemUse.select(p, st -> st.is(Items.WARPED_FUNGUS_ON_A_STICK));
    }
}
