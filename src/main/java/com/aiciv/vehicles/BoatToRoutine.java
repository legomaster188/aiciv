package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.world.Riding;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/** Crosses water to x z: set a boat down (or use one beside the bot), paddle there, get out, and take the boat along. */
public final class BoatToRoutine extends Routine {
    private static final double NEAR_BOAT = 8;
    private final int x;
    private final int z;
    private final RideToAction ride;
    private int step;
    private int timeout = 24000 + 1200;
    private Entity boat;

    public BoatToRoutine(int x, int z) {
        this.x = x;
        this.z = z;
        this.ride = new RideToAction("boat_to", x, z);
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        var p = ctx.player();
        switch (step++) {
            case 0: {
                Vec3 here = ctx.position();
                double distance = Math.hypot(x + 0.5 - here.x, z + 0.5 - here.z);
                if (distance > RideToAction.MAX_DISTANCE) {
                    return new Fail("boat_to", "goes at most " + RideToAction.MAX_DISTANCE + " blocks per call");
                }
                timeout = RideToAction.timeoutFor(distance) + 1200;
                if (p.isPassenger() && p.getVehicle() instanceof AbstractBoat) return nextStage(ctx);
                if (Mounts.find(p, "boat", NEAR_BOAT).isPresent()) return nextStage(ctx);
                return new PlaceBoatAction();
            }
            case 1:
                if (p.isPassenger() && p.getVehicle() instanceof AbstractBoat) return nextStage(ctx);
                return new BoardAction("boat");
            case 2:
                return ride;
            case 3:
                boat = p.getVehicle();
                return new DismountAction();
            case 4:
                return boat == null ? null : new RecoverAction(boat);
            default:
                return null;
        }
    }

    @Override
    public void cancel(BotContext ctx) {
        super.cancel(ctx);
        if (ctx.player() != null && ctx.player().isPassenger()) Riding.dismount(ctx.player());
    }

    @Override
    protected String name() {
        return "boat to " + x + " " + z;
    }

    @Override
    protected String summary() {
        return "crossed to " + x + " " + z + " by boat";
    }

    @Override
    public int timeoutTicks() {
        return timeout;
    }

    /** A stage that fails at once, so a routine can refuse in its first step. */
    static final class Fail implements Action {
        private final String name;
        private final String reason;

        Fail(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }

        @Override
        public ActionStatus tick(BotContext ctx) {
            return ActionStatus.failed(reason);
        }

        @Override
        public String describe() {
            return name;
        }

        @Override
        public int timeoutTicks() {
            return 1;
        }
    }
}
