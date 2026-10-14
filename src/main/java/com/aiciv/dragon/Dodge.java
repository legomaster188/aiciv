package com.aiciv.dragon;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;

/**
 * Steps out of dragon breath: when the feet are within a cloud's radius + 1, walks to a point about 6 blocks from the
 * cloud's centre, on the side away from the fountain, and never toward a cell with no floor within 3 blocks below.
 */
final class Dodge {
    static final double MARGIN = 1.0;
    static final double DISTANCE = 6.0;
    private static final int REPLAN_TICKS = 5;

    private boolean active;
    private Vec3 goal;
    private int sincePlan = REPLAN_TICKS;
    private final List<Vec3> failed = new ArrayList<>();

    /** True while the bot is stepping out of breath: the caller should do nothing else this tick. */
    boolean tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        List<AreaEffectCloud> clouds = DragonView.breath(p);
        AreaEffectCloud worst = null;
        double worstDepth = 0;
        for (AreaEffectCloud c : clouds) {
            double depth = c.getRadius() + MARGIN - horizontal(p.position(), c.position());
            if (depth > worstDepth && Math.abs(p.getY() - c.getY()) <= 4) {
                worstDepth = depth;
                worst = c;
            }
        }
        if (worst == null) {
            if (active) {
                ctx.navigator().stop();
                active = false;
                goal = null;
                failed.clear();
            }
            return false;
        }
        sincePlan++;
        NavStatus nav = ctx.navigator().status();
        if (active && nav.state() == NavStatus.State.RUNNING) return true;
        if (active && nav.state() == NavStatus.State.FAILED && goal != null) failed.add(goal);
        if (sincePlan < REPLAN_TICKS) return active;
        sincePlan = 0;
        Vec3 target = choose(p, worst, clouds);
        if (target == null) return false;
        goal = target;
        active = true;
        ctx.navigator().goTo(new NearGoal(target.x, target.y, target.z, 1.5));
        return true;
    }

    private Vec3 choose(AiPlayer p, AreaEffectCloud cloud, List<AreaEffectCloud> clouds) {
        Vec3 centre = cloud.position();
        Vec3 away = awayFromFountain(p, centre);
        double base = Math.atan2(away.z, away.x);
        double distance = Math.max(DISTANCE, cloud.getRadius() + 3);
        BlockPos feet = p.blockPosition();
        for (Vec3 c : Ground.ring(new LevelBlockView(p.level()), centre.x, centre.z, distance, base, feet.getY())) {
            if (failed.stream().anyMatch(f -> f.distanceTo(c) < 2)) continue;
            boolean clear = true;
            for (AreaEffectCloud other : clouds) {
                if (horizontal(c, other.position()) <= other.getRadius() + MARGIN + 0.5) clear = false;
            }
            if (clear) return c;
        }
        return null;
    }

    /** From the fountain through the cloud; with no dragon to say where the fountain is, from the cloud through the bot. */
    private static Vec3 awayFromFountain(AiPlayer p, Vec3 centre) {
        Vec3 v = DragonView.dragon(p).map(EnderDragon::getFightOrigin)
                .map(o -> new Vec3(centre.x - (o.getX() + 0.5), 0, centre.z - (o.getZ() + 0.5)))
                .orElse(new Vec3(p.getX() - centre.x, 0, p.getZ() - centre.z));
        return v.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : v;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** Forget the goal (the action was cancelled or a reflex moved the bot). */
    void reset() {
        active = false;
        goal = null;
        sincePlan = REPLAN_TICKS;
        failed.clear();
    }
}
