package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.combat.BowShot;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * Fights the dragon for a number of seconds: steps out of its breath first, hits its head while it perches (never
 * from the middle of the fountain) and shoots its body with a bow while it flies.
 */
public final class HitDragonAction implements Action {
    static final double HEAD_RADIUS = 2.8;
    static final double BOW_RANGE = 48;
    static final int CHARGE_TICKS = 20;
    static final int BOW_COOLDOWN = 15;
    static final int MAX_APPROACH_FAILURES = 3;
    /** The fountain's centre 3 x 3, where the exit portal's bedrock column stands. */
    static final double COLUMN_HALF_WIDTH = 1.5;
    static final double COLUMN_LEAVE_RADIUS = 4.5;

    private final int seconds;
    private final boolean anyDimension;
    private final Dodge dodge = new Dodge();
    private boolean started;
    private boolean dodging;
    private int ticks;
    private int hits;
    private Approach approach;
    private int approachFailures;
    private BowShot shot;
    private int cooldown;
    private boolean leavingColumn;
    private String summary;

    public HitDragonAction(int seconds) {
        this(seconds, false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public HitDragonAction(int seconds, boolean anyDimension) {
        this.seconds = Math.max(5, Math.min(120, seconds));
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            started = true;
            String problem = TheEnd.problem(ctx, "hit_dragon", anyDimension);
            if (problem != null) return ActionStatus.failed(problem);
        }
        if (dodge.tick(ctx)) {
            if (!dodging) {
                dodging = true;
                approach = null; // the dodge owns the navigator now; plan the approach afresh afterwards
                leavingColumn = false;
            }
            dropShot(p);
            return ActionStatus.RUNNING;
        }
        dodging = false;
        EnderDragon dragon = DragonView.dragon(p).orElse(null);
        if (dragon == null) return ActionStatus.failed("no ender dragon within " + (int) DragonView.DRAGON_RANGE + " blocks");
        if (DragonView.dead(dragon)) return done("the dragon is dead", ctx);
        if (++ticks > seconds * 20) return done("hit the dragon " + hits + " time" + (hits == 1 ? "" : "s") + " (health " + health(dragon) + ")", ctx);
        if (cooldown > 0) cooldown--;
        if (leaveColumn(ctx, p, dragon)) return ActionStatus.RUNNING;
        if (DragonView.perched(dragon)) {
            dropShot(p);
            return hitHead(ctx, p, dragon);
        }
        if (approach != null) {
            approach.cancel(ctx);
            approach = null;
        }
        shootBody(p, dragon);
        return ActionStatus.RUNNING;
    }

    private ActionStatus hitHead(BotContext ctx, AiPlayer p, EnderDragon dragon) {
        if (approach == null) approach = Approach.entity(dragon.head, HEAD_RADIUS);
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.FAILED) {
            approach = null;
            if (++approachFailures >= MAX_APPROACH_FAILURES) return ActionStatus.failed("can't reach the dragon's head: " + s.reason());
            return ActionStatus.RUNNING;
        }
        if (s.state() == Approach.State.ARRIVED && p.getAttackStrengthScale(0.5F) >= 0.9F
                && p.isWithinEntityInteractionRange(dragon.head, 0.0)) {
            InventoryOps.selectBest(p, AttackAction::weaponScore);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, dragon.head.getBoundingBox().getCenter());
            p.attack(dragon.head);
            p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            hits++;
        }
        return ActionStatus.RUNNING;
    }

    private void shootBody(AiPlayer p, EnderDragon dragon) {
        boolean armed = InventoryOps.has(p, Items.BOW, 1) && InventoryOps.count(p, s -> s.is(ItemTags.ARROWS)) > 0;
        if (!armed || p.distanceTo(dragon) > BOW_RANGE || !p.hasLineOfSight(dragon)) {
            dropShot(p);
            return;
        }
        if (shot == null) {
            if (cooldown > 0) return;
            shot = new BowShot(() -> dragon.getBoundingBox().getCenter(), CHARGE_TICKS);
        }
        BowShot.State state = shot.tick(p);
        if (state != BowShot.State.DRAWING) {
            shot = null;
            cooldown = BOW_COOLDOWN;
        }
    }

    /** Steps off the exit portal's bedrock column, to a spot a few blocks out from the fountain's centre. */
    private boolean leaveColumn(BotContext ctx, AiPlayer p, EnderDragon dragon) {
        BlockPos origin = dragon.getFightOrigin();
        double dx = p.getX() - (origin.getX() + 0.5), dz = p.getZ() - (origin.getZ() + 0.5);
        boolean onColumn = Math.abs(dx) <= COLUMN_HALF_WIDTH && Math.abs(dz) <= COLUMN_HALF_WIDTH;
        if (!onColumn) {
            if (leavingColumn && ctx.navigator().status().state() != NavStatus.State.RUNNING) leavingColumn = false;
            return leavingColumn;
        }
        if (leavingColumn && ctx.navigator().status().state() == NavStatus.State.RUNNING) return true;
        List<Vec3> spots = Ground.ring(new LevelBlockView(p.level()), origin.getX() + 0.5, origin.getZ() + 0.5, COLUMN_LEAVE_RADIUS,
                Math.atan2(dz, dx), p.blockPosition().getY());
        if (spots.isEmpty()) return false;
        approach = null;
        leavingColumn = true;
        ctx.navigator().goTo(new NearGoal(spots.get(0).x, spots.get(0).y, spots.get(0).z, 1.0));
        return true;
    }

    private ActionStatus done(String text, BotContext ctx) {
        summary = text;
        cancel(ctx);
        return ActionStatus.DONE;
    }

    private static String health(EnderDragon d) {
        return Math.round(d.getHealth()) + "/" + Math.round(d.getMaxHealth());
    }

    private void dropShot(AiPlayer p) {
        if (shot != null) shot.cancel(p);
        shot = null;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.player() != null) dropShot(ctx.player());
        if (approach != null) approach.cancel(ctx);
        approach = null;
        ctx.navigator().stop();
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (ctx.player() != null) dropShot(ctx.player());
        approach = null;
        leavingColumn = false;
        dodge.reset();
        dodging = false;
    }

    @Override
    public String describe() {
        return summary != null ? summary : "hit the dragon for " + seconds + " seconds";
    }

    @Override
    public int timeoutTicks() {
        return seconds * 20 + 200;
    }
}
