package com.aiciv.flight;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.Riding;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

/** Glides to a point: takes off, steers toward it with {@link ElytraPilot}, fires rockets to keep height, and lands. */
public final class FlyToAction implements Action {
    static final double MIN_DISTANCE = 16, MAX_DISTANCE = 1024;
    /** Landing this close to the target counts as arriving. */
    static final double ARRIVE_RADIUS = 16;
    static final int STALL_TICKS = 5;

    private final int x, z;
    private boolean started;
    private LaunchElytraAction launch;
    private int distance;
    private int rocketsAtStart;
    private int sinceRocket = ElytraPilot.ROCKET_GAP;
    private int stalled;
    private double startY;
    private String summary;

    public FlyToAction(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            started = true;
            double d = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ());
            distance = (int) Math.round(d);
            if (d < MIN_DISTANCE || d > MAX_DISTANCE) {
                return ActionStatus.failed("fly_to goes 16 to 1024 blocks (this one is " + distance + ")");
            }
            rocketsAtStart = rockets(p);
            if (p.isFallFlying()) startY = p.getY();
            else launch = new LaunchElytraAction();
        }
        if (launch != null) {
            ActionStatus s = launch.tick(ctx);
            if (s.state() != ActionStatus.State.DONE) return s;
            launch = null;
            startY = p.getY();
        }
        if (!p.isFallFlying()) {
            if (p.onGround() || p.isInWater()) return landed(p);
            if (++stalled >= STALL_TICKS) {
                p.tryToStartFallFlying();
                stalled = 0;
            }
            return ActionStatus.RUNNING;
        }
        stalled = 0;
        fly(p);
        return ActionStatus.RUNNING;
    }

    private void fly(AiPlayer p) {
        double dx = x + 0.5 - p.getX(), dz = z + 0.5 - p.getZ();
        int groundY = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
        int spare = Math.min(rockets(p), ElytraPilot.MAX_ROCKETS - used(p));
        ElytraPilot.Command c = ElytraPilot.next(new ElytraPilot.State(dx, dz, p.getY(), groundY,
                p.getDeltaMovement().length(), Math.max(0, spare), sinceRocket, startY));
        float yaw = Riding.yawTo(dx, dz);
        if (c.rocket() && ItemUse.select(p, s -> s.is(Items.FIREWORK_ROCKET))) {
            ItemUse.useFacing(p, yaw, c.pitch());
            sinceRocket = 0;
        } else {
            Riding.faceYaw(p, yaw);
            p.setXRot(c.pitch());
            sinceRocket++;
        }
    }

    private ActionStatus landed(AiPlayer p) {
        double away = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ());
        if (away > ARRIVE_RADIUS) {
            return ActionStatus.failed("landed at " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ() + ", "
                    + Math.round(away) + " blocks short");
        }
        int used = used(p);
        summary = "flew to " + x + " " + z + " (used " + used + (used == 1 ? " rocket)" : " rockets)");
        return ActionStatus.DONE;
    }

    private static int rockets(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(Items.FIREWORK_ROCKET));
    }

    private int used(AiPlayer p) {
        return Math.max(0, rocketsAtStart - rockets(p));
    }

    @Override
    public String describe() {
        return summary != null ? summary : "flyTo " + x + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return 600 + 10 * distance;
    }
}
