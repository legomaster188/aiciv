package com.aiciv.combat;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Ballistics;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.Riding;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * One bow shot: draw, keep aiming at a (possibly moving) target while the bow charges, release. Tick once per server
 * tick until it is no longer DRAWING; the arrow flies with vanilla physics, so the aim allows for its drop.
 */
public final class BowShot {
    public enum State { DRAWING, RELEASED, FAILED }

    private final Supplier<Vec3> target;
    private final int chargeTicks;
    private State state;
    private String reason = "";

    public BowShot(Supplier<Vec3> target, int chargeTicks) {
        this.target = target;
        this.chargeTicks = Math.max(1, Math.min(30, chargeTicks));
    }

    public State tick(AiPlayer p) {
        if (state == State.RELEASED || state == State.FAILED) return state;
        if (state == null) {
            if (!InventoryOps.has(p, Items.BOW, 1)) return fail(p, "no bow");
            if (InventoryOps.count(p, s -> s.is(ItemTags.ARROWS)) == 0) return fail(p, "no arrows");
            InventoryOps.selectBest(p, s -> s.is(Items.BOW) ? 1 : 0);
            if (!aim(p)) return fail(p, "out of bow range");
            p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (!p.isUsingItem()) return fail(p, "could not draw the bow");
            state = State.DRAWING;
            return state;
        }
        if (!p.isUsingItem()) return fail(p, "could not draw the bow");
        if (!aim(p)) return fail(p, "out of bow range");
        if (p.getTicksUsingItem() >= chargeTicks) {
            p.releaseUsingItem();
            state = State.RELEASED;
        }
        return state;
    }

    private boolean aim(AiPlayer p) {
        Optional<float[]> look = ItemUse.aimThrow(p, Ballistics.Kind.ARROW, BowItem.getPowerForTime(chargeTicks) * 3.0, target.get());
        if (look.isEmpty()) return false;
        Riding.faceYaw(p, look.get()[0]);
        p.setXRot(look.get()[1]);
        return true;
    }

    private State fail(AiPlayer p, String why) {
        cancel(p);
        reason = why;
        state = State.FAILED;
        return state;
    }

    /** Why the shot failed; empty otherwise. */
    public String reason() {
        return reason;
    }

    /** Lowers the bow without shooting. */
    public void cancel(AiPlayer p) {
        if (p.isUsingItem()) p.stopUsingItem();
    }
}
