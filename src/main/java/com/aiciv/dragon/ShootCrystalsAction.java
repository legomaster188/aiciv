package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.combat.BowShot;
import com.aiciv.nav.LevelBlockView;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.Ballistics;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Shoots open (uncaged) end crystals with a bow, nearest first, from at least 9 blocks away: the explosion of a
 * crystal hurts anyone close by. Up to 4 arrows per crystal.
 */
public final class ShootCrystalsAction implements Action {
    static final double SAFE_DISTANCE = 9;
    static final double BACK_OFF_TO = 11;
    static final int MAX_ARROWS_PER_CRYSTAL = 4;
    static final int CHARGE_TICKS = 20;
    static final int MAX_MOVE_FAILURES = 3;
    static final double FULL_DRAW_SPEED = 3.0;

    private final int count;
    private final boolean anyDimension;
    private final Dodge dodge = new Dodge();
    private final Set<Integer> gaveUp = new HashSet<>();
    private boolean started;
    private int destroyed;
    private EndCrystal target;
    private int shots;
    private BowShot shot;
    private int waitTicks;
    private boolean moving;
    private int moveFailures;
    private String summary;
    private String lastGaveUp;

    public ShootCrystalsAction(int count) {
        this(count, false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public ShootCrystalsAction(int count, boolean anyDimension) {
        this.count = Math.max(1, Math.min(10, count));
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            started = true;
            String problem = TheEnd.problem(ctx, "shoot_crystals", anyDimension);
            if (problem != null) return ActionStatus.failed(problem);
            if (!InventoryOps.has(p, Items.BOW, 1)) return ActionStatus.failed("needs a bow");
            if (arrows(p) == 0) return ActionStatus.failed("needs arrows (you have 0)");
        }
        if (dodge.tick(ctx)) {
            dropShot(p);
            moving = false;
            return ActionStatus.RUNNING;
        }
        if (target != null && target.isRemoved()) {
            if (shots > 0) destroyed++;
            target = null;
            dropShot(p);
        }
        if (destroyed >= count) return finish(ctx, p);
        if (target == null) {
            Optional<EndCrystal> next = pick(p);
            if (next.isEmpty()) {
                if (destroyed > 0) return finish(ctx, p);
                return ActionStatus.failed(lastGaveUp != null ? lastGaveUp : "no open end crystal in sight");
            }
            target = next.get();
            shots = 0;
            waitTicks = 0;
            moveFailures = 0;
            moving = false;
        }
        if (waitTicks > 0) {
            if (--waitTicks == 0 && shots >= MAX_ARROWS_PER_CRYSTAL) {
                giveUp("the end crystal at " + coords(target) + " survived " + shots + " arrows");
            }
            return ActionStatus.RUNNING;
        }
        if (shot == null && arrows(p) == 0) return ActionStatus.failed("out of arrows");
        if (shot == null && !DragonView.visible(p, target)) {
            // Something moved between the bot and the crystal: look for another.
            gaveUp.add(target.getId());
            target = null;
            moving = false;
            return ActionStatus.RUNNING;
        }
        double d = p.distanceTo(target);
        if (shot == null && d < SAFE_DISTANCE && !moving) return backOff(ctx, p);
        if (moving) {
            NavStatus nav = ctx.navigator().status();
            if (nav.state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
            moving = false;
            if (nav.state() == NavStatus.State.FAILED) return moveFailed(ctx, nav.reason());
            return ActionStatus.RUNNING;
        }
        if (shot == null && ItemUse.aimThrow(p, Ballistics.Kind.ARROW, FULL_DRAW_SPEED, centre(target)).isEmpty()) {
            return closeIn(ctx, p, d);
        }
        if (shot == null) {
            EndCrystal aimed = target;
            shot = new BowShot(() -> centre(aimed), CHARGE_TICKS);
        }
        BowShot.State state = shot.tick(p);
        if (state == BowShot.State.RELEASED) {
            shot = null;
            shots++;
            waitTicks = 10 + (int) (d / 2);
        } else if (state == BowShot.State.FAILED) {
            String reason = shot.reason();
            shot = null;
            if (reason.equals("out of bow range")) return closeIn(ctx, p, d);
            return ActionStatus.failed(reason);
        }
        return ActionStatus.RUNNING;
    }

    private Optional<EndCrystal> pick(AiPlayer p) {
        return DragonView.crystals(p).stream()
                .filter(c -> !gaveUp.contains(c.getId()) && !DragonView.caged(p.level(), c) && DragonView.visible(p, c))
                .findFirst();
    }

    /** Walks to a spot at least 10 blocks from the crystal, on the side the bot already stands. */
    private ActionStatus backOff(BotContext ctx, AiPlayer p) {
        Vec3 c = target.position();
        double base = Math.atan2(p.getZ() - c.z, p.getX() - c.x);
        List<Vec3> spots = Ground.ring(new LevelBlockView(p.level()), c.x, c.z, BACK_OFF_TO, base, p.blockPosition().getY());
        if (spots.isEmpty()) return moveFailed(ctx, "nowhere to stand " + (int) BACK_OFF_TO + " blocks from the crystal");
        walkTo(ctx, spots.get(0));
        return ActionStatus.RUNNING;
    }

    /** Out of range: steps 8 blocks closer, but no nearer than 10 to the crystal. */
    private ActionStatus closeIn(BotContext ctx, AiPlayer p, double distance) {
        Vec3 c = target.position();
        double want = Math.max(BACK_OFF_TO - 1, distance - 8);
        double base = Math.atan2(p.getZ() - c.z, p.getX() - c.x);
        List<Vec3> spots = Ground.ring(new LevelBlockView(p.level()), c.x, c.z, want, base, p.blockPosition().getY());
        if (spots.isEmpty()) return moveFailed(ctx, "no way closer to the crystal at " + coords(target));
        walkTo(ctx, spots.get(0));
        return ActionStatus.RUNNING;
    }

    private void walkTo(BotContext ctx, Vec3 spot) {
        moving = true;
        ctx.navigator().goTo(new NearGoal(spot.x, spot.y, spot.z, 1.5));
    }

    private ActionStatus moveFailed(BotContext ctx, String reason) {
        ctx.navigator().stop();
        if (++moveFailures >= MAX_MOVE_FAILURES) {
            giveUp("could not get a clear shot at the end crystal at " + coords(target) + ": " + reason);
        }
        return ActionStatus.RUNNING;
    }

    private void giveUp(String why) {
        gaveUp.add(target.getId());
        lastGaveUp = why;
        target = null;
        moving = false;
    }

    private ActionStatus finish(BotContext ctx, AiPlayer p) {
        ctx.navigator().stop();
        dropShot(p);
        int left = (int) DragonView.crystals(p).stream()
                .filter(c -> !DragonView.caged(p.level(), c) && DragonView.visible(p, c)).count();
        summary = "destroyed " + destroyed + " crystal" + (destroyed == 1 ? "" : "s") + " (" + left + " left in sight)";
        return ActionStatus.DONE;
    }

    private void dropShot(AiPlayer p) {
        if (shot != null) shot.cancel(p);
        shot = null;
    }

    private static Vec3 centre(EndCrystal c) {
        return c.position().add(0, 1, 0);
    }

    private static int arrows(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(ItemTags.ARROWS));
    }

    private static String coords(EndCrystal c) {
        return c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.player() != null) dropShot(ctx.player());
        ctx.navigator().stop();
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (ctx.player() != null) dropShot(ctx.player());
        moving = false;
        waitTicks = 0;
        dodge.reset();
    }

    @Override
    public String describe() {
        return summary != null ? summary : "shoot " + count + " end crystal" + (count == 1 ? "" : "s");
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }
}
