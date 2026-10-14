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
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Gets the bot into the nether by the cheapest means: a portal it can see or remembers, a frame to light, or a new portal. */
public final class GoToNetherRoutine extends Routine {
    static final int PORTAL_SIGHT = 32;
    static final int FRAME_SIGHT = 16;
    static final int WAYPOINT_RANGE = 128;
    /** Farther than this from a portal the enter action cannot see it, so the routine walks closer first. */
    static final double WALK_FIRST = 12;
    static final double WALK_RADIUS = 4;

    private final Deque<Action> stages = new ArrayDeque<>();
    private boolean planned;
    private EnterPortalAction enter;

    @Override
    protected Action nextStage(BotContext ctx) {
        if (!planned) {
            planned = true;
            AiPlayer p = ctx.player();
            if (Portals.key(p.level()).equals("minecraft:the_nether")) return new Refusal("go_to_nether", "you are already in the_nether");
            plan(ctx, p);
        }
        return stages.poll();
    }

    private void plan(BotContext ctx, AiPlayer p) {
        enter = new EnterPortalAction("nether");
        List<BlockPos> portals = Finder.visibleStates(p, Portals.ofKind("nether"), PORTAL_SIGHT);
        if (!portals.isEmpty()) {
            BlockPos portal = portals.get(0);
            if (p.blockPosition().distSqr(portal) > WALK_FIRST * WALK_FIRST) stages.add(new WalkToAction(portal, WALK_RADIUS));
        } else if (!rememberedPortal(ctx, p)) {
            List<BlockPos> frames = LightPortalAction.framesInSight(p, FRAME_SIGHT);
            stages.add(frames.isEmpty() ? new BuildPortalAction(null, null) : new LightPortalAction(frames.get(0)));
        }
        stages.add(enter);
    }

    private static boolean rememberedPortal(BotContext ctx, AiPlayer p) {
        if (ctx.attachments() == null) return false;
        Optional<Waypoints.Waypoint> w = ctx.attachments().get(Waypoints.class, Waypoints::new)
                .nearest(Waypoints.PORTAL, Portals.key(p.level()), new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        if (w.isEmpty()) return false;
        double dx = w.get().pos().x() - p.getX(), dy = w.get().pos().y() - p.getY(), dz = w.get().pos().z() - p.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) WAYPOINT_RANGE * WAYPOINT_RANGE;
    }

    @Override
    protected String summary() {
        return enter != null ? enter.describe() : null;
    }

    @Override
    protected String name() {
        return "go to the nether";
    }

    @Override
    public int timeoutTicks() {
        return 36000;
    }
}
