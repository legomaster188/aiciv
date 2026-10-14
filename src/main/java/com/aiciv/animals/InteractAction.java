package com.aiciv.animals;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.EntityUse;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Walk up to an entity, hold an item and right-click it until the wanted change shows. The animal actions that are
 * one click (saddle, lead, milk, shears, dye) only say what to find, what to hold and when they are done.
 */
abstract class InteractAction implements Action {
    static final double REACH = 2.5;
    /** Clicks that did not bring the change before the action gives up. */
    static final int MAX_ATTEMPTS = 10;

    protected final String label;
    protected Entity target;
    private Entity approaching;
    private Approach approach;
    private int attempts;
    private boolean finished;
    private String result;

    protected InteractAction(String label) {
        this.label = label;
    }

    /** Picks {@link #target}; returns a failure reason, or null when it has one. Called until it succeeds. */
    protected abstract String find(AiPlayer p);

    /** Selects what the click needs; false when the bot no longer has it. */
    protected abstract boolean hold(AiPlayer p);

    /** What to tell the model when {@link #hold} says no. */
    protected abstract String missing();

    protected abstract boolean done(AiPlayer p);

    /** The line reported once done, e.g. "milked the cow". */
    protected abstract String success(AiPlayer p);

    protected abstract String verb();

    /** The entity to walk to and click; a lead tied to a fence goes through the fence's knot first. */
    protected Entity clickTarget(AiPlayer p) {
        return target;
    }

    protected Approach approachTo(Entity click) {
        return Approach.entity(click, REACH);
    }

    protected BlockUse.Result click(AiPlayer p, Entity click) {
        return EntityUse.interact(p, click, InteractionHand.MAIN_HAND);
    }

    /** Called once when the change shows; may start collecting drops. */
    protected void onDone(BotContext ctx) {}

    /** Called each tick after that; true while something (collecting drops) is still going on. */
    protected boolean afterwards(BotContext ctx) {
        return false;
    }

    protected void stopAfterwards(BotContext ctx) {}

    /** The finished line; shears add what they dropped once it is collected. */
    protected String summary() {
        return result;
    }

    protected String stuck() {
        return "the " + label + " would not take it";
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (target == null) {
            String failure = find(p);
            if (failure != null) return ActionStatus.failed(failure);
        }
        if (finished) return afterwards(ctx) ? ActionStatus.RUNNING : ActionStatus.DONE;
        if (!target.isAlive()) return ActionStatus.failed("the " + label + " is gone");
        if (done(p)) {
            result = success(p);
            finished = true;
            if (approach != null) approach.cancel(ctx);
            onDone(ctx);
            return afterwards(ctx) ? ActionStatus.RUNNING : ActionStatus.DONE;
        }
        Entity click = clickTarget(p);
        if (approach == null || approaching != click) {
            if (approach != null) approach.cancel(ctx);
            approaching = click;
            approach = approachTo(click);
        }
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        if (!hold(p)) return ActionStatus.failed(missing());
        BlockUse.Result r = click(p, click);
        if (r == BlockUse.Result.USED && click != target) return ActionStatus.RUNNING; // a step toward the target, not the change itself
        if (!done(p) && ++attempts > MAX_ATTEMPTS) return ActionStatus.failed(stuck());
        return ActionStatus.RUNNING;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        stopAfterwards(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        approach = null;
    }

    @Override
    public String describe() {
        return finished ? summary() : verb() + " " + label;
    }

    @Override
    public int timeoutTicks() {
        return 600;
    }
}
