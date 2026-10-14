package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.world.Riding;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/** Puts a minecart on a rail (unless one is beside the bot), rides it until it stops, then takes the cart along. */
public final class RideMinecartRoutine extends Routine {
    private static final double NEAR_CART = 8;
    private int step;
    private Entity cart;
    private CartRideAction ride;

    @Override
    protected Action nextStage(BotContext ctx) {
        var p = ctx.player();
        switch (step++) {
            case 0:
                if (p.getVehicle() instanceof AbstractMinecart || Mounts.find(p, "minecart", NEAR_CART).isPresent()) return nextStage(ctx);
                return new PlaceMinecartAction(null);
            case 1:
                if (p.getVehicle() instanceof AbstractMinecart) return nextStage(ctx);
                return new BoardAction("minecart");
            case 2:
                ride = new CartRideAction();
                return ride;
            case 3:
                cart = p.getVehicle();
                return new DismountAction();
            case 4:
                return cart == null ? null : new RecoverAction(cart);
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
        return "ride a minecart";
    }

    @Override
    protected String summary() {
        return ride != null ? "rode a minecart; " + ride.describe() : "rode a minecart";
    }

    @Override
    public int timeoutTicks() {
        return 4800;
    }
}
