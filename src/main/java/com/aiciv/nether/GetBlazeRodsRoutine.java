package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.Pos;
import java.util.Optional;

/** Finds a fortress unless one is known nearby, then hunts blazes there. */
public final class GetBlazeRodsRoutine extends Routine {
    static final int FORTRESS_RANGE = 128;
    static final int BRICKS_RANGE = 24;
    static final int SEARCH_DISTANCE = 384;

    private final int count;
    private int step;
    private HuntBlazesAction hunt;

    public GetBlazeRodsRoutine(int count) {
        this.count = count;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        int s = step++;
        if (s == 0 && !fortressKnown(ctx)) return new FindFortressAction(null, SEARCH_DISTANCE);
        if (hunt != null) return null;
        hunt = new HuntBlazesAction(count);
        return hunt;
    }

    private static boolean fortressKnown(BotContext ctx) {
        AiPlayer p = ctx.player();
        Pos here = new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        if (ctx.attachments() != null) {
            Optional<Waypoints.Waypoint> w = ctx.attachments().get(Waypoints.class, Waypoints::new)
                    .nearest(Waypoints.FORTRESS, Portals.key(p.level()), here);
            if (w.isPresent() && distance(w.get().pos(), here) <= FORTRESS_RANGE) return true;
        }
        if (ctx.knownBlocks() == null) return false;
        return ctx.knownBlocks().nearest(label -> label.equals("nether_bricks"), here)
                .filter(b -> distance(b, here) <= BRICKS_RANGE).isPresent();
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    protected String summary() {
        return hunt != null ? hunt.describe() : null;
    }

    @Override
    protected String name() {
        return "get " + count + " blaze rods";
    }

    @Override
    public int timeoutTicks() {
        return 36000;
    }
}
