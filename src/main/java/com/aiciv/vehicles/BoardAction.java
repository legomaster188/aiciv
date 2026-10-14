package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Riding;
import java.util.Locale;
import net.minecraft.world.entity.Entity;

/** Walks up to the nearest free boat, minecart or mount and gets on with an empty hand. */
public final class BoardAction implements Action {
    private static final int MAX_CLICKS = 3;
    private final String kind;
    private Entity vehicle;
    private Approach approach;
    private int clicks;
    private String result;

    public BoardAction(String kind) {
        this.kind = kind.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (vehicle == null) {
            if (p.isPassenger() && p.getVehicle() != null && Mounts.kindOf(p.getVehicle()).equals(kind)) return done(p, p.getVehicle());
            vehicle = Mounts.find(p, kind, Mounts.RANGE).orElse(null);
            if (vehicle == null) return ActionStatus.failed("no " + kind + " within 16 blocks");
            String problem = Mounts.problem(p, vehicle);
            if (problem != null) return ActionStatus.failed(problem);
            approach = Approach.entity(vehicle, 2.5);
        }
        if (p.getVehicle() == vehicle) return done(p, vehicle);
        if (vehicle.isVehicle() && p.getVehicle() != vehicle) return ActionStatus.failed("the " + kind + " is taken");
        Approach.Status s = approach.tick(ctx);
        switch (s.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                return ActionStatus.failed(s.reason());
            default:
                break;
        }
        BlockUse.Result r = Riding.mount(p, vehicle);
        if (r == BlockUse.Result.USED) return done(p, vehicle);
        if (++clicks >= MAX_CLICKS || r == BlockUse.Result.NO_EFFECT) return ActionStatus.failed("could not get on the " + kind);
        approach = Approach.entity(vehicle, 2.0); // out of reach or hidden: step closer and try again
        return ActionStatus.RUNNING;
    }

    private ActionStatus done(AiPlayer p, Entity v) {
        Mounts.holdSteeringItem(p, v);
        result = "riding the " + kind;
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (vehicle != null) approach = Approach.entity(vehicle, 2.5);
    }

    @Override
    public String describe() {
        return result != null ? result : "board " + kind;
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
