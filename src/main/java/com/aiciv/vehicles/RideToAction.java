package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Riding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

/** Drives what the bot is riding toward x z with only the inputs a client sends. */
public final class RideToAction implements Action {
    static final int MAX_DISTANCE = 512;
    private static final double ARRIVED = 3.0;
    private static final int STUCK_WINDOW = 60;
    private static final double STUCK_MOVE = 0.5;
    private static final int JUMP_EVERY = 20;
    private static final float DEADBAND = 10f;

    private final int x;
    private final int z;
    private final String tool;
    private Entity vehicle;
    private String kind;
    private int ticks;
    private int timeout = 24000;
    private Vec3 windowStart;
    private int lastJump = -JUMP_EVERY;
    private String result;

    public RideToAction(int x, int z) {
        this("ride_to", x, z);
    }

    RideToAction(String tool, int x, int z) {
        this.tool = tool;
        this.x = x;
        this.z = z;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (vehicle == null) {
            ActionStatus start = start(ctx, p);
            if (start != null) return start;
        }
        if (p.getVehicle() != vehicle || !vehicle.isAlive()) {
            Riding.clearInput(p);
            BlockPos at = p.blockPosition();
            return ActionStatus.failed("you fell off the " + kind + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
        }
        ticks++;
        double dx = x + 0.5 - vehicle.getX(), dz = z + 0.5 - vehicle.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= ARRIVED) {
            Riding.clearInput(p);
            result = "rode the " + kind + " to " + x + " " + z;
            return ActionStatus.DONE;
        }
        if (ticks % STUCK_WINDOW == 0) {
            if (vehicle.position().distanceTo(windowStart) < STUCK_MOVE) {
                Riding.clearInput(p);
                BlockPos at = vehicle.blockPosition();
                return ActionStatus.failed("the " + kind + " is stuck at " + at.getX() + " " + at.getZ()
                        + (vehicle instanceof AbstractBoat ? " (land in the way of the boat?)" : ""));
            }
            windowStart = vehicle.position();
        }
        float desired = Riding.yawTo(dx, dz);
        if (vehicle instanceof AbstractBoat boat) {
            Steering.Turn turn = Steering.turn(boat.getYRot(), desired, DEADBAND);
            boat.setInput(turn == Steering.Turn.LEFT, turn == Steering.Turn.RIGHT, Steering.throttle(boat.getYRot(), desired), false);
            Riding.faceYaw(p, boat.getYRot());
        } else if (vehicle instanceof AbstractMinecart) {
            Riding.pushInput(p, true, false, false, false);
        } else {
            Mounts.holdSteeringItem(p, vehicle);
            Riding.faceYaw(p, desired);
            Riding.pushInput(p, true, false, false, false);
            if (vehicle instanceof AbstractHorse horse && vehicle.horizontalCollision && ticks - lastJump >= JUMP_EVERY) {
                lastJump = ticks;
                horse.onPlayerJump(100);
                horse.handleStartJump(100);
            }
        }
        return ActionStatus.RUNNING;
    }

    /** Null when ready to drive; otherwise the failure. */
    private ActionStatus start(BotContext ctx, AiPlayer p) {
        Vec3 here = ctx.position();
        double distance = Math.hypot(x + 0.5 - here.x, z + 0.5 - here.z);
        if (distance > MAX_DISTANCE) return ActionStatus.failed(tool + " goes at most " + MAX_DISTANCE + " blocks per call");
        Entity v = p.getVehicle();
        if (v == null) return ActionStatus.failed("you are not riding anything (use board first)");
        String problem = Mounts.problem(p, v);
        if (problem != null) return ActionStatus.failed(problem);
        vehicle = v;
        kind = Mounts.kindOf(v);
        windowStart = v.position();
        timeout = timeoutFor(distance);
        return null;
    }

    static int timeoutFor(double distance) {
        return (int) Math.min(24000, 600 + 20 * distance);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.player() != null && ctx.player().isPassenger()) Riding.dismount(ctx.player());
    }

    @Override
    public void interrupted(BotContext ctx) {
        cancel(ctx);
    }

    @Override
    public String describe() {
        return result != null ? result : "ride to " + x + " " + z;
    }

    @Override
    public int timeoutTicks() {
        return timeout;
    }
}
