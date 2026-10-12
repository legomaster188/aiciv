package com.aiciv.actions;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** Walks a given distance along a compass direction, to see what is there. */
public final class ExploreAction implements Action {
    static final double ARRIVE_RADIUS = 8;
    private static final double DIAGONAL = Math.sqrt(0.5);
    /** North is -Z and east is +X, as in the game. */
    private static final Map<String, double[]> DIRECTIONS = Map.ofEntries(
            Map.entry("n", new double[] {0, -1}), Map.entry("north", new double[] {0, -1}),
            Map.entry("ne", new double[] {DIAGONAL, -DIAGONAL}), Map.entry("northeast", new double[] {DIAGONAL, -DIAGONAL}),
            Map.entry("e", new double[] {1, 0}), Map.entry("east", new double[] {1, 0}),
            Map.entry("se", new double[] {DIAGONAL, DIAGONAL}), Map.entry("southeast", new double[] {DIAGONAL, DIAGONAL}),
            Map.entry("s", new double[] {0, 1}), Map.entry("south", new double[] {0, 1}),
            Map.entry("sw", new double[] {-DIAGONAL, DIAGONAL}), Map.entry("southwest", new double[] {-DIAGONAL, DIAGONAL}),
            Map.entry("w", new double[] {-1, 0}), Map.entry("west", new double[] {-1, 0}),
            Map.entry("nw", new double[] {-DIAGONAL, -DIAGONAL}), Map.entry("northwest", new double[] {-DIAGONAL, -DIAGONAL}));

    private final String direction;
    private final int distance;
    private final double[] unit;
    private boolean started;
    private ColumnGoal goal;

    public ExploreAction(String direction, int distance) {
        this.unit = DIRECTIONS.get(key(direction));
        if (unit == null) throw new IllegalArgumentException("direction must be N, NE, E, SE, S, SW, W or NW");
        this.direction = direction.trim().toUpperCase(Locale.ROOT);
        this.distance = distance;
    }

    public static boolean isDirection(String direction) {
        return direction != null && DIRECTIONS.containsKey(key(direction));
    }

    /** "North-East", "north east" and "NE" are all the same direction. */
    private static String key(String direction) {
        return direction == null ? "" : direction.toLowerCase(Locale.ROOT).replaceAll("[\s_-]+", "");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!started) {
            started = true;
            if (goal == null) {
                Vec3 p = ctx.position();
                goal = new ColumnGoal(p.x + unit[0] * distance, p.z + unit[1] * distance, ARRIVE_RADIUS);
            }
            ctx.navigator().goTo(goal);
            return ActionStatus.RUNNING;
        }
        NavStatus s = ctx.navigator().status();
        return switch (s.state()) {
            case RUNNING -> ActionStatus.RUNNING;
            case ARRIVED -> ActionStatus.DONE;
            case FAILED -> ActionStatus.failed(s.reason());
            case IDLE -> ActionStatus.failed("navigation stopped");
        };
    }

    @Override
    public void interrupted(BotContext ctx) {
        started = false; // walk on toward the same spot
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "explore " + direction + " " + distance;
    }

    @Override
    public int timeoutTicks() {
        return 3 * 60 * 20;
    }
}
