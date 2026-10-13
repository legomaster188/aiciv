package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.Sight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Walks until the bot's eyes are within a radius of a block or entity <b>and</b> it can see it, so a following click
 * never reaches through a wall. Used by every action that uses a block or entity; tick it until it stops running.
 */
public final class Approach {
    public enum State { RUNNING, ARRIVED, FAILED }

    public record Status(State state, String reason) {}

    static final double RETRY_RADIUS = 1.5;
    static final int MAX_NAV_FAILURES = 3;
    static final double REPLAN_DISTANCE = 2.0;
    static final int REPLAN_TICKS = 10;
    /** The navigator measures from the feet; aiming its goal this far below the target makes that the eyes' distance. */
    private static final double EYE_HEIGHT = 1.62;
    /** Slack for the bot not standing exactly at its block's centre. */
    private static final double GOAL_SLACK = 0.5;
    /**
     * Smallest goal radius. The goal sits 1.62 below the target so the eyes end up level with it, but a block at floor
     * level then puts the goal 1.12 below any standable cell; anything under about 1.5 could never be reached.
     */
    private static final double MIN_GOAL_RADIUS = 1.6;
    private static final Status RUNNING = new Status(State.RUNNING, "");
    private static final Status ARRIVED = new Status(State.ARRIVED, "");

    private final BlockPos block;
    private final Entity entity;
    private final double radius;
    private String what;
    private boolean walking;
    private boolean retried;
    private int navFailures;
    private boolean failureCounted;
    private int idleTicks;
    private Vec3 anchor;

    private Approach(BlockPos block, Entity entity, double radius) {
        this.block = block;
        this.entity = entity;
        this.radius = radius;
    }

    public static Approach block(BlockPos target, double radius) {
        return new Approach(target.immutable(), null, radius);
    }

    public static Approach entity(Entity target, double radius) {
        return new Approach(null, target, radius);
    }

    public Status tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (what == null) what = describe(p);
        if (entity != null && (entity.isRemoved() || !entity.isAlive() || entity.level() != p.level())) {
            return fail(ctx, what + " is gone");
        }
        Vec3 centre = centre();
        boolean near = p.getEyePosition().distanceTo(centre) <= radius;
        boolean seen = entity == null ? Sight.canSee(p, block) : p.hasLineOfSight(entity);
        if (near && seen) {
            if (walking) ctx.navigator().stop();
            walking = false;
            return ARRIVED;
        }
        NavStatus nav = ctx.navigator().status();
        boolean running = walking && nav.state() == NavStatus.State.RUNNING;
        if (retried && !running) return fail(ctx, "can't see " + what + " at " + coords() + " from anywhere near");
        if (near) {
            // Close enough but hidden: walk right up to it once and look again.
            if (running) return RUNNING;
            retried = true;
            walk(ctx, RETRY_RADIUS);
            return RUNNING;
        }
        if (!walking) {
            walk(ctx, radius);
            return RUNNING;
        }
        if (nav.state() == NavStatus.State.FAILED) {
            if (!failureCounted) {
                failureCounted = true;
                if (++navFailures >= MAX_NAV_FAILURES) return fail(ctx, nav.reason());
            }
            walk(ctx, radius);
            return RUNNING;
        }
        if (running) {
            idleTicks = 0;
            if (entity != null && anchor.distanceTo(centre) > REPLAN_DISTANCE) walk(ctx, radius);
            return RUNNING;
        }
        // The navigator stopped short (arrived by its own measure, or was stopped): try again after a moment.
        if (++idleTicks >= REPLAN_TICKS) {
            if (entity == null && ++navFailures >= MAX_NAV_FAILURES) {
                return fail(ctx, "could not get within " + (int) Math.ceil(radius) + " blocks of " + what + " at " + coords());
            }
            walk(ctx, radius);
        }
        return RUNNING;
    }

    public void cancel(BotContext ctx) {
        if (walking) ctx.navigator().stop();
        walking = false;
    }

    private void walk(BotContext ctx, double r) {
        Vec3 c = centre();
        anchor = c;
        walking = true;
        failureCounted = false;
        idleTicks = 0;
        ctx.navigator().goTo(new NearGoal(c.x, c.y - EYE_HEIGHT, c.z, Math.max(MIN_GOAL_RADIUS, r - GOAL_SLACK)));
    }

    private Status fail(BotContext ctx, String reason) {
        cancel(ctx);
        return new Status(State.FAILED, reason);
    }

    private Vec3 centre() {
        return entity == null ? Vec3.atCenterOf(block) : entity.getBoundingBox().getCenter();
    }

    /** "chest", "zombie" or a player's name, for failure reasons. */
    private String describe(AiPlayer p) {
        if (entity == null) return BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(block).getBlock()).getPath();
        if (entity instanceof Player player) return player.getScoreboardName();
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    private String coords() {
        BlockPos at = entity == null ? block : entity.blockPosition();
        return at.getX() + " " + at.getY() + " " + at.getZ();
    }
}
