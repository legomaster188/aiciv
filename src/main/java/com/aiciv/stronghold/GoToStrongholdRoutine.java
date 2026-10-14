package com.aiciv.stronghold;

import com.aiciv.actions.Action;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.Routine;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.Pos;
import com.aiciv.world.InventoryOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.Items;

/**
 * From a stronghold estimate to the portal room: walk to within 24 blocks, throw eyes until one sinks (walking on
 * along the flight between throws), dig down on that spot until bricks show, then search the stronghold.
 */
public final class GoToStrongholdRoutine extends Routine {
    static final double LEG = 128;
    static final double NEAR = 24;
    static final int MAX_THROWS = 8;
    /** Smaller than the eye's 12-block dive zone, so a step along a flight cannot skip over it. */
    static final double STEP = 16;
    static final int MIN_Y = -50;

    private enum Phase { WALK_IN, THROW, AFTER_THROW, TO_SPOT, DIG, FIND, END }

    private final boolean anyDimension;
    private Phase phase = Phase.WALK_IN;
    private Waypoints.Waypoint target;
    private int thrown;
    private EyeReadings.Reading lastSeen;
    private double spotX, spotZ;
    private String summary;

    public GoToStrongholdRoutine() {
        this(false);
    }

    public GoToStrongholdRoutine(boolean anyDimension) {
        this.anyDimension = anyDimension;
    }

    @Override
    protected Action nextStage(BotContext ctx) {
        AiPlayer p = ctx.player();
        EyeReadings readings = ctx.attachments().get(EyeReadings.class, EyeReadings::new);
        while (true) {
            switch (phase) {
                case WALK_IN -> {
                    if (target == null) {
                        Optional<Waypoints.Waypoint> found = ctx.attachments().get(Waypoints.class, Waypoints::new)
                                .nearest(Waypoints.STRONGHOLD, ctx.dimension(), here(p));
                        if (found.isEmpty()) return new Fail(name(), "no stronghold estimate; use locate_stronghold first");
                        target = found.get();
                        spotX = target.pos().x() + 0.5;
                        spotZ = target.pos().z() + 0.5;
                    }
                    double dx = spotX - p.getX(), dz = spotZ - p.getZ();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d <= NEAR) {
                        phase = Phase.THROW;
                        continue;
                    }
                    if (d <= LEG + NEAR) return new WalkColumnAction(spotX, spotZ, NEAR, "the stronghold");
                    return new WalkColumnAction(p.getX() + dx / d * LEG, p.getZ() + dz / d * LEG, 8, "the stronghold");
                }
                case THROW -> {
                    boolean eyes = InventoryOps.count(p, s -> s.is(Items.ENDER_EYE)) > 0;
                    if (thrown >= MAX_THROWS || !eyes) {
                        phase = Phase.TO_SPOT;
                        continue;
                    }
                    thrown++;
                    List<EyeReadings.Reading> all = readings.all();
                    lastSeen = all.isEmpty() ? null : all.get(all.size() - 1);
                    phase = Phase.AFTER_THROW;
                    return new ThrowEyeAction(anyDimension);
                }
                case AFTER_THROW -> {
                    List<EyeReadings.Reading> all = readings.all();
                    EyeReadings.Reading r = all.isEmpty() ? null : all.get(all.size() - 1);
                    if (r == null || r == lastSeen) {
                        phase = Phase.TO_SPOT; // the throw left no reading
                        continue;
                    }
                    if (r.sank()) {
                        spotX = r.x();
                        spotZ = r.z();
                        ctx.attachments().get(Waypoints.class, Waypoints::new).mark(Waypoints.STRONGHOLD, ctx.dimension(),
                                new Pos((int) Math.round(r.x()), LocateStrongholdRoutine.WAYPOINT_Y, (int) Math.round(r.z())),
                                "an eye sank here", p.level().getGameTime());
                        phase = Phase.TO_SPOT;
                        continue;
                    }
                    phase = Phase.THROW;
                    double b = Math.toRadians(r.bearing());
                    return new WalkColumnAction(p.getX() + Math.sin(b) * STEP, p.getZ() - Math.cos(b) * STEP, 4, "the flight of the eye");
                }
                case TO_SPOT -> {
                    phase = Phase.DIG;
                    double dx = spotX - p.getX(), dz = spotZ - p.getZ();
                    if (dx * dx + dz * dz > 9) return new WalkColumnAction(spotX, spotZ, 2, "the stronghold");
                }
                case DIG -> {
                    phase = Phase.FIND;
                    return new DigToStrongholdAction(MIN_Y, anyDimension);
                }
                case FIND -> {
                    phase = Phase.END;
                    return new FindPortalRoomAction(64, anyDimension);
                }
                case END -> {
                    Optional<Waypoints.Waypoint> room = ctx.attachments().get(Waypoints.class, Waypoints::new)
                            .nearest(Waypoints.END_PORTAL, ctx.dimension(), here(p));
                    summary = room.map(w -> "reached the stronghold; the end portal room is at " + w.pos().x() + " " + w.pos().y() + " " + w.pos().z())
                            .orElse("reached the stronghold");
                    return null;
                }
            }
        }
    }

    private static Pos here(AiPlayer p) {
        return new Pos(p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ());
    }

    @Override
    protected String name() {
        return "go to the stronghold";
    }

    @Override
    protected String summary() {
        return summary;
    }

    @Override
    public int timeoutTicks() {
        return 72000;
    }
}
