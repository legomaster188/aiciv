package com.aiciv.dragon;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.EnterPortalAction;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;

/** Walks to the fountain and through the exit portal, which only exists once the dragon is dead. */
public final class LeaveEndRoutine extends Routine {
    private final boolean anyDimension;
    private int step;

    public LeaveEndRoutine() {
        this(false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public LeaveEndRoutine(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        int s = step++;
        if (s == 0) {
            String problem = TheEnd.problem(ctx, "leave_end", anyDimension);
            if (problem != null) return new Fail("leave the End", problem);
            AiPlayer p = ctx.player();
            if (DragonView.dragon(p).filter(d -> !DragonView.dead(d)).isPresent()) {
                return new Fail("leave the End", "the exit portal opens when the dragon is dead");
            }
            if (FightDragonRoutine.exitPortalVisible(p)) {
                step = 2;
                return new EnterPortalAction("end");
            }
            return new WalkToFountain();
        }
        return s == 1 ? new EnterPortalAction("end") : null;
    }

    @Override
    protected String name() {
        return "leave the End";
    }

    @Override
    public int timeoutTicks() {
        return 6000;
    }

    /** Gets within a few blocks of the fountain, where the dragon's fight is centred (0, 0 once the dragon is gone). */
    private static final class WalkToFountain implements Action {
        static final int RADIUS = 6;
        static final int MAX_NAV_FAILURES = 3;
        private boolean walking;
        private int navFailures;

        @Override
        public ActionStatus tick(BotContext ctx) {
            AiPlayer p = ctx.player();
            if (FightDragonRoutine.exitPortalVisible(p)) {
                ctx.navigator().stop();
                return ActionStatus.DONE;
            }
            NavStatus nav = ctx.navigator().status();
            if (walking && nav.state() == NavStatus.State.ARRIVED) return ActionStatus.DONE;
            if (walking && nav.state() == NavStatus.State.RUNNING) return ActionStatus.RUNNING;
            if (walking && nav.state() == NavStatus.State.FAILED && ++navFailures >= MAX_NAV_FAILURES) {
                return ActionStatus.failed("could not reach the fountain: " + nav.reason());
            }
            BlockPos origin = DragonView.dragon(p).map(EnderDragon::getFightOrigin).orElse(BlockPos.ZERO);
            if (!p.level().isLoaded(origin)) return ActionStatus.failed("the fountain is too far away to walk to");
            int y = Math.max(origin.getY(), p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, origin.getX(), origin.getZ()));
            walking = true;
            ctx.navigator().goTo(new NearGoal(origin.getX() + 0.5, y, origin.getZ() + 0.5, RADIUS));
            return ActionStatus.RUNNING;
        }

        @Override
        public void cancel(BotContext ctx) {
            ctx.navigator().stop();
        }

        @Override
        public void interrupted(BotContext ctx) {
            walking = false;
        }

        @Override
        public String describe() {
            return "walk to the fountain";
        }

        @Override
        public int timeoutTicks() {
            return 3000;
        }
    }
}
