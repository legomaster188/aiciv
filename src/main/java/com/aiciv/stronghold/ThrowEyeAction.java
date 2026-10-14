package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.perception.Compass;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Throws an eye of ender toward the horizon, watches where it flies, remembers the bearing and picks the eye up again
 * if it survived. Eyes only fly in a dimension that has a stronghold to find.
 */
public final class ThrowEyeAction implements Action {
    static final float PITCH = -10f;
    static final int FOLLOW_TICKS = 100;
    static final int GATHER_TICKS = 120;
    static final double DROP_RADIUS = 3.0;

    private enum Stage { THROW, WATCH, FOLLOW, GATHER }

    private final boolean anyDimension;
    private Stage stage = Stage.THROW;
    private EyeWatcher watcher;
    private EyeReadings.Reading reading;
    private int stageTicks;
    private int eyesBefore;
    private boolean sawDrop;
    private ItemGatherer gatherer;
    private String summary;

    public ThrowEyeAction() {
        this(false);
    }

    public ThrowEyeAction(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return switch (stage) {
            case THROW -> throwEye(ctx, p);
            case WATCH -> watch(ctx, p);
            case FOLLOW -> follow(ctx, p);
            case GATHER -> gather(ctx, p);
        };
    }

    private ActionStatus throwEye(BotContext ctx, AiPlayer p) {
        String problem = Overworld.problem(ctx, "throw_eye", anyDimension);
        if (problem != null) return ActionStatus.failed(problem);
        eyesBefore = InventoryOps.count(p, s -> s.is(Items.ENDER_EYE));
        if (eyesBefore < 1) return ActionStatus.failed("needs 1 ender_eye (you have 0)");
        ItemUse.select(p, s -> s.is(Items.ENDER_EYE));
        watcher = EyeWatcher.start(p, EyeWatcher.nearbyEyes(p));
        ItemUse.useFacing(p, p.getYRot(), PITCH);
        stage = Stage.WATCH;
        return ActionStatus.RUNNING;
    }

    private ActionStatus watch(BotContext ctx, AiPlayer p) {
        switch (watcher.tick(p)) {
            case NONE -> {
                p.stopUsingItem();
                return ActionStatus.failed("the eye does not fly here: no stronghold in this dimension");
            }
            case READ -> {
                reading = watcher.reading();
                if (ctx.attachments() != null) ctx.attachments().get(EyeReadings.class, EyeReadings::new).add(reading);
                stage = Stage.FOLLOW;
                stageTicks = 0;
                if (reading.sank()) return finish(p, false);
            }
            case WAITING -> { }
        }
        return ActionStatus.RUNNING;
    }

    /** The eye keeps flying for about 80 ticks; it can only be picked up where it ends. */
    private ActionStatus follow(BotContext ctx, AiPlayer p) {
        watcher.tick(p);
        if (!watcher.eyeGone() && ++stageTicks < FOLLOW_TICKS) return ActionStatus.RUNNING;
        stage = Stage.GATHER;
        stageTicks = 0;
        Vec3 drop = watcher.dropPos().orElse(p.position());
        gatherer = new ItemGatherer(c -> eyesNear(c, drop));
        return ActionStatus.RUNNING;
    }

    private ActionStatus gather(BotContext ctx, AiPlayer p) {
        boolean more = gatherer.tick(ctx);
        sawDrop |= more;
        if (more && ++stageTicks < GATHER_TICKS) return ActionStatus.RUNNING;
        gatherer.stop(ctx);
        int now = InventoryOps.count(p, s -> s.is(Items.ENDER_EYE));
        return finish(p, !sawDrop && now < eyesBefore);
    }

    private static List<ItemEntity> eyesNear(BotContext ctx, Vec3 drop) {
        return ctx.player().level().getEntitiesOfClass(ItemEntity.class, ctx.player().getBoundingBox().inflate(48),
                e -> e.getItem().is(Items.ENDER_EYE)
                        && Math.hypot(e.getX() - drop.x, e.getZ() - drop.z) <= DROP_RADIUS);
    }

    private ActionStatus finish(AiPlayer p, boolean broke) {
        if (reading.sank()) {
            Vec3 at = watcher.readPosition();
            summary = "the eye sank at " + Math.round(at.x) + " " + Math.round(at.y) + " " + Math.round(at.z)
                    + ": the stronghold is right below";
        } else {
            double b = reading.bearing();
            String dir = Compass.direction(Math.sin(Math.toRadians(b)), -Math.cos(Math.toRadians(b)));
            summary = "the eye flew toward " + Math.round(b) + "° (" + dir + ") from " + Math.round(reading.x()) + " "
                    + Math.round(reading.z()) + (broke ? " (the eye broke)" : "");
        }
        return ActionStatus.DONE;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (gatherer != null) gatherer.stop(ctx);
        if (ctx.player() != null) ctx.player().stopUsingItem();
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (gatherer != null) gatherer.stop(ctx);
    }

    @Override
    public String describe() {
        return summary != null ? summary : "throw an eye of ender";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }
}
