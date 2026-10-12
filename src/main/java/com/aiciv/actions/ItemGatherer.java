package com.aiciv.actions;

import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Walks to dropped items one at a time; vanilla picks them up when the bot gets close. */
final class ItemGatherer {
    private static final double CLOSE = 1.5;
    /** Ticks spent on one item before it counts as unreachable or unpickable (e.g. a full inventory). */
    private static final int GIVE_UP_TICKS = 100;
    /** A falling item moves, so the walk is re-aimed this often while the navigator is idle. */
    private static final int REAIM_TICKS = 10;
    private static final double MOVED = 2.0;
    private static final int AIR_WAIT_TICKS = 40;

    private final Function<BotContext, List<ItemEntity>> finder;
    private final Set<Integer> ignored = new HashSet<>();
    private int targetId = -1;
    private int effort;
    private int sinceGoTo;
    private int airborne;
    private Vec3 goal;

    ItemGatherer(Function<BotContext, List<ItemEntity>> finder) {
        this.finder = finder;
    }

    /** Steps toward the nearest item. Returns false once there is nothing left to walk to. */
    boolean tick(BotContext ctx) {
        Vec3 here = ctx.position();
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : finder.apply(ctx)) {
            if (!item.isAlive() || ignored.contains(item.getId())) continue;
            double d = item.position().distanceToSqr(here);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest == null) return false;
        int id = nearest.getId();
        if (id != targetId) {
            targetId = id;
            effort = 0;
            airborne = 0;
            goal = null;
        }
        if (++effort > GIVE_UP_TICKS) {
            ignored.add(id);
            return true;
        }
        if (Math.sqrt(best) <= CLOSE) return true; // close enough: only the pickup delay is left
        // A fresh drop is still falling; walking toward a spot in mid-air would only fail, so wait for it to land.
        if (!nearest.onGround() && !nearest.isInWater() && ++airborne <= AIR_WAIT_TICKS) return true;
        NavStatus status = ctx.navigator().status();
        if (goal != null && status.state() == NavStatus.State.FAILED) {
            ignored.add(id);
            return true;
        }
        sinceGoTo++;
        boolean idle = status.state() != NavStatus.State.RUNNING;
        boolean moved = goal != null && goal.distanceToSqr(nearest.position()) > MOVED * MOVED;
        if (goal == null || moved || (idle && sinceGoTo >= REAIM_TICKS)) {
            goal = nearest.position();
            sinceGoTo = 0;
            ctx.navigator().goTo(new NearGoal(goal.x, goal.y, goal.z, 1.0));
        }
        return true;
    }

    void stop(BotContext ctx) {
        if (targetId >= 0) ctx.navigator().stop();
        targetId = -1;
    }
}
