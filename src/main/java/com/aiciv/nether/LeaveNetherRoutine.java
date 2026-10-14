package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.EnterPortalAction;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Walks back to the nearest known nether portal and takes it home. */
public final class LeaveNetherRoutine extends Routine {
    static final int SIGHT = 16;
    static final double WALK_RADIUS = 4;

    private final Deque<Action> stages = new ArrayDeque<>();
    private boolean planned;
    private EnterPortalAction enter;

    @Override
    protected Action nextStage(BotContext ctx) {
        if (!planned) {
            planned = true;
            String wrong = Refusal.notInNether(ctx, "leave_nether");
            if (wrong != null) return new Refusal("leave_nether", wrong);
            AiPlayer p = ctx.player();
            enter = new EnterPortalAction("nether");
            if (Finder.visibleStates(p, Portals.ofKind("nether"), SIGHT).isEmpty()) {
                Optional<Waypoints.Waypoint> known = ctx.attachments() == null ? Optional.empty()
                        : ctx.attachments().get(Waypoints.class, Waypoints::new)
                                .nearest(Waypoints.PORTAL, Portals.key(p.level()), new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
                if (known.isEmpty()) return new Refusal("leave_nether", "no known portal in the_nether; build one with build_portal");
                Pos at = known.get().pos();
                stages.add(new WalkToAction(new BlockPos(at.x(), at.y(), at.z()), WALK_RADIUS));
            }
            stages.add(enter);
        }
        return stages.poll();
    }

    @Override
    protected String summary() {
        return enter != null ? enter.describe() : null;
    }

    @Override
    protected String name() {
        return "leave the nether";
    }

    @Override
    public int timeoutTicks() {
        return 36000;
    }
}
