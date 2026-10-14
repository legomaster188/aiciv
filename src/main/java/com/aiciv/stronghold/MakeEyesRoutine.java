package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.CraftAction;
import com.aiciv.actions.Routine;
import com.aiciv.world.InventoryOps;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.item.Items;

/** Eyes of ender from pearls and blaze powder, grinding blaze rods into powder first when the powder is short. */
public final class MakeEyesRoutine extends Routine {
    private final int count;
    private Deque<Action> plan;

    public MakeEyesRoutine(int count) {
        this.count = count;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        if (plan == null) plan = plan(ctx);
        return plan.poll();
    }

    private Deque<Action> plan(BotContext ctx) {
        Deque<Action> stages = new ArrayDeque<>();
        int pearls = InventoryOps.count(ctx.player(), s -> s.is(Items.ENDER_PEARL));
        if (pearls < count) {
            stages.add(new Fail(name(), "needs " + (count - pearls) + " more ender_pearl"));
            return stages;
        }
        int powder = InventoryOps.count(ctx.player(), s -> s.is(Items.BLAZE_POWDER));
        int rods = InventoryOps.count(ctx.player(), s -> s.is(Items.BLAZE_ROD));
        int short_ = count - powder;
        if (short_ > 0) {
            int rodsNeeded = (short_ + 1) / 2;
            if (rodsNeeded > rods) {
                stages.add(new Fail(name(), "needs " + (rodsNeeded - rods) + " more blaze_rod"));
                return stages;
            }
            // CraftAction counts what it should hold afterwards; a rod makes two powder.
            stages.add(new CraftAction("blaze_powder", short_));
        }
        stages.add(new CraftAction("ender_eye", count));
        return stages;
    }

    @Override
    protected String name() {
        return "make " + count + " ender_eye";
    }

    @Override
    protected String summary() {
        return "made " + count + " ender_eye";
    }

    @Override
    public int timeoutTicks() {
        return 2400;
    }
}
