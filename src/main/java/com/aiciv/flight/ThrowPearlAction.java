package com.aiciv.flight;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Ballistics;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.Sight;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Throws an ender pearl at a block it can see and lets the real pearl carry it there, for the real 5 health. */
public final class ThrowPearlAction implements Action {
    /** At this health or below the pearl's 5 damage could leave the bot one hit from death. */
    static final float TOO_HURT = 7f;
    static final int MIN_RANGE = 3, MAX_RANGE = 40;
    static final int COOLDOWN_WAIT = 20, LAND_WAIT = 100;
    /** A step this long in one tick can only be the pearl's teleport. */
    static final double TELEPORT_STEP = 2.0;
    static final double ARRIVE_RADIUS = 4.0;
    /** Aim just above the landing block's top face so the pearl comes down on it. */
    private static final double AIM_ABOVE_TOP = 0.05;

    private final int x, y, z;
    private boolean thrown;
    private int waited;
    private float healthBefore;
    private Vec3 last;
    private String summary;

    public ThrowPearlAction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return thrown ? watch(p) : prepare(p);
    }

    private ActionStatus prepare(AiPlayer p) {
        if (p.getHealth() <= TOO_HURT) {
            return ActionStatus.failed("too hurt to pearl (it costs 5 health; you have " + (int) p.getHealth() + ")");
        }
        if (InventoryOps.count(p, s -> s.is(Items.ENDER_PEARL)) == 0) return ActionStatus.failed("no ender_pearl");
        double away = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ());
        if (away < MIN_RANGE || away > MAX_RANGE) return ActionStatus.failed("a pearl lands 3 to 40 blocks away");
        if (!Sight.canSee(p, new BlockPos(x, y, z))) {
            return ActionStatus.failed("can't see where to land at " + x + " " + y + " " + z);
        }
        ItemUse.select(p, s -> s.is(Items.ENDER_PEARL));
        if (p.getCooldowns().isOnCooldown(p.getMainHandItem())) {
            return ++waited > COOLDOWN_WAIT ? ActionStatus.failed("the ender_pearl is still on cooldown") : ActionStatus.RUNNING;
        }
        Optional<float[]> aim = ItemUse.aimThrow(p, Ballistics.Kind.THROWN, new Vec3(x + 0.5, y + 1 + AIM_ABOVE_TOP, z + 0.5));
        if (aim.isEmpty()) return ActionStatus.failed("too far to throw a pearl there");
        healthBefore = p.getHealth();
        if (!ItemUse.useFacing(p, aim.get()[0], aim.get()[1]).consumesAction()) return ActionStatus.failed("could not throw the pearl");
        thrown = true;
        waited = 0;
        last = p.position();
        return ActionStatus.RUNNING;
    }

    private ActionStatus watch(AiPlayer p) {
        Vec3 now = p.position();
        if (now.distanceTo(last) > TELEPORT_STEP) {
            if (now.distanceTo(new Vec3(x + 0.5, y + 1, z + 0.5)) > ARRIVE_RADIUS) {
                return ActionStatus.failed("the pearl landed at " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ() + " instead");
            }
            long damage = Math.round(Math.max(0f, healthBefore - p.getHealth()));
            summary = "pearled to " + x + " " + y + " " + z + " (took " + damage + " damage)";
            return ActionStatus.DONE;
        }
        last = now;
        return ++waited > LAND_WAIT ? ActionStatus.failed("the pearl never carried you anywhere (it may have hit something)") : ActionStatus.RUNNING;
    }

    @Override
    public String describe() {
        return summary != null ? summary : "throwPearl " + x + " " + y + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }
}
