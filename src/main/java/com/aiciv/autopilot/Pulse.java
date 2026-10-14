package com.aiciv.autopilot;

import com.aiciv.bot.AiPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;

/** A cheap reading between decisions, for urgent replanning without a snapshot. */
public record Pulse(int health, int food, boolean night, String hostileWithin8, long tick) {
    private static final double RANGE = 8;

    /** Server thread. One entity query: monsters in the bot's box inflated by 8; the nearest within 8 blocks counts. */
    public static Pulse read(AiPlayer p, long tick) {
        Monster nearest = null;
        double best = RANGE;
        for (Monster m : p.level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(RANGE), LivingEntity::isAlive)) {
            double d = m.distanceTo(p);
            if (d <= best) {
                best = d;
                nearest = m;
            }
        }
        String hostile = nearest == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).getPath();
        return new Pulse(Math.round(p.getHealth()), p.getFoodData().getFoodLevel(), p.level().isDarkOutside(), hostile, tick);
    }
}
