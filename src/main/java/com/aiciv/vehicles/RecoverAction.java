package com.aiciv.vehicles;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Riding;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks a boat or minecart with bare hands and picks the drop up. Never fails: the ride it follows already worked, so a
 * vehicle that cannot be reached or an item that cannot be fetched only ends the clean-up.
 */
final class RecoverAction implements Action {
    /**
     * A vehicle breaks on the hit after its damage passes 40. Bare hands add 10 a hit and the damage heals 1 a tick, so at
     * full swing strength (every 5 ticks) it takes about nine hits.
     */
    private static final int MAX_HITS = 24;
    private static final float FULL_SWING = 0.9f;
    private static final int GATHER_TICKS = 100;
    private static final double DROP_RADIUS = 6;
    private static final double REACH = 2.5;

    private final Entity vehicle;
    private Approach approach;
    private ItemGatherer gatherer;
    private Vec3 where;
    private int hits;
    private int gathering;
    private String result = "recovered the vehicle";

    RecoverAction(Entity vehicle) {
        this.vehicle = vehicle;
        this.where = vehicle.position();
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (vehicle.isAlive()) {
            where = vehicle.position();
            if (p.isPassenger()) Riding.dismount(p);
            if (hits >= MAX_HITS || vehicle.level() != p.level()) return give("left the " + Mounts.kindOf(vehicle));
            if (approach == null) approach = Approach.entity(vehicle, REACH);
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            if (s.state() == Approach.State.FAILED) return give("left the " + Mounts.kindOf(vehicle));
            if (p.getAttackStrengthScale(0.5f) >= FULL_SWING) {
                hits++;
                p.lookAt(EntityAnchorArgument.Anchor.EYES, vehicle.getBoundingBox().getCenter());
                p.attack(vehicle);
                p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
            return ActionStatus.RUNNING;
        }
        if (gatherer == null) {
            if (approach != null) approach.cancel(ctx);
            gatherer = new ItemGatherer(c -> drops(c));
            result = "got the " + Mounts.kindOf(vehicle) + " back";
        }
        if (++gathering > GATHER_TICKS || !gatherer.tick(ctx)) {
            gatherer.stop(ctx);
            return ActionStatus.DONE;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus give(String text) {
        result = text;
        return ActionStatus.DONE;
    }

    private List<ItemEntity> drops(BotContext ctx) {
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class, new AABB(where, where).inflate(DROP_RADIUS),
                i -> i.isAlive() && isVehicleItem(i));
    }

    private boolean isVehicleItem(ItemEntity i) {
        String id = BuiltInRegistries.ITEM.getKey(i.getItem().getItem()).getPath();
        return vehicle instanceof AbstractBoat ? id.endsWith("_boat") || id.endsWith("_raft") : id.contains("minecart");
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        if (gatherer != null) gatherer.stop(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (vehicle.isAlive()) approach = null;
    }

    @Override
    public String describe() {
        return result;
    }

    @Override
    public int timeoutTicks() {
        return 900;
    }
}
