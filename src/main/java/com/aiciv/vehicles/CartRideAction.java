package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Riding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/** Rides a minecart along its rails until it comes to rest. */
final class CartRideAction implements Action {
    private static final double STILL = 0.01;
    private static final int STILL_TICKS = 40;
    /** One short of the timeout, so a cart still rolling after two minutes ends the ride instead of failing it. */
    private static final int MAX_TICKS = 2300;
    private Entity cart;
    private int ticks;
    private int still;
    private String result;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (cart == null) {
            cart = p.getVehicle();
            if (!(cart instanceof AbstractMinecart)) return ActionStatus.failed("you are not in a minecart");
        }
        if (p.getVehicle() != cart) {
            Riding.clearInput(p);
            return ActionStatus.failed("you fell out of the minecart");
        }
        ticks++;
        still = cart.getDeltaMovement().horizontalDistance() < STILL ? still + 1 : 0;
        if (still >= STILL_TICKS || ticks >= MAX_TICKS) {
            Riding.clearInput(p);
            BlockPos at = cart.blockPosition();
            result = "the minecart " + (still >= STILL_TICKS ? "stopped" : "was still rolling") + " at " + at.getX() + " " + at.getY() + " " + at.getZ();
            return ActionStatus.DONE;
        }
        Riding.pushInput(p, true, false, false, false);
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.player() != null && ctx.player().isPassenger()) Riding.dismount(ctx.player());
    }

    /** A reflex only lets go of the keys; the cart rolls on and the ride carries on afterwards. */
    @Override
    public void interrupted(BotContext ctx) {
        if (ctx.player() != null) Riding.clearInput(ctx.player());
        still = 0;
    }

    @Override
    public String describe() {
        return result != null ? result : "ride the minecart";
    }

    @Override
    public int timeoutTicks() {
        return MAX_TICKS + 100;
    }
}
