package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ExploreAction;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import java.util.List;
import java.util.Optional;

/**
 * Finds the stronghold by throwing an eye, walking sideways, throwing again and crossing the two flights. If they are
 * too close to parallel to cross, it walks on and throws a third time (the widest pair of the readings is used).
 */
public final class LocateStrongholdRoutine extends Routine {
    static final int MAX_THROWS = 3;
    /** The stronghold's y in the waypoint; strongholds lie between about 6 and 50, and the exact height is not known. */
    static final int WAYPOINT_Y = 30;

    private final int spread;
    private final boolean anyDimension;
    private int thrown;
    private boolean explored;
    private boolean retriedExplore;
    private String summary;

    public LocateStrongholdRoutine(int spread) {
        this(spread, false);
    }

    public LocateStrongholdRoutine(int spread, boolean anyDimension) {
        this.spread = spread;
        this.anyDimension = anyDimension;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        EyeReadings readings = ctx.attachments().get(EyeReadings.class, EyeReadings::new);
        if (thrown > 0 && readings.estimate().isPresent()) return finish(ctx, readings);
        if (thrown >= MAX_THROWS) {
            return new Fail(name(), "the eye flights are almost parallel, so they do not cross; try a larger spread");
        }
        if (thrown == 0 || explored) {
            thrown++;
            explored = false;
            return new ThrowEyeAction(anyDimension);
        }
        explored = true;
        return new ExploreAction(sideways(readings, false), spread);
    }

    /** The 8-point direction at a right angle to the last flight. */
    private static String sideways(EyeReadings readings, boolean opposite) {
        List<EyeReadings.Reading> all = readings.all();
        double b = all.get(all.size() - 1).bearing() + (opposite ? -90 : 90);
        return Compass.direction(Math.sin(Math.toRadians(b)), -Math.cos(Math.toRadians(b)));
    }

    @Override
    protected Action retry(BotContext ctx, Action failed, String reason) {
        if (!(failed instanceof ExploreAction) || retriedExplore) return null;
        retriedExplore = true;
        return new ExploreAction(sideways(ctx.attachments().get(EyeReadings.class, EyeReadings::new), true), spread);
    }

    private Action finish(BotContext ctx, EyeReadings readings) {
        double[] at = readings.estimate().orElseThrow();
        AiPlayer p = ctx.player();
        int x = (int) Math.round(at[0]), z = (int) Math.round(at[1]);
        long tick = p.level().getGameTime();
        String dimension = ctx.dimension();
        String note = readings.all().stream().anyMatch(EyeReadings.Reading::sank) ? "an eye sank here"
                : "estimate from " + readings.all().size() + " eyes";
        ctx.attachments().get(Waypoints.class, Waypoints::new).mark(Waypoints.STRONGHOLD, dimension, new Pos(x, WAYPOINT_Y, z), note, tick);
        double dx = x - p.getX(), dz = z - p.getZ();
        summary = "the stronghold is about " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks " + Compass.direction(dx, dz)
                + " at " + x + " " + z;
        return null;
    }

    @Override
    protected String name() {
        return "locate the stronghold";
    }

    @Override
    protected String summary() {
        return summary;
    }

    @Override
    public int timeoutTicks() {
        return 12000;
    }
}
