package com.aiciv.stronghold;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Watches a thrown eye of ender the way a player does: only where the entity is, tick by tick. Where the eye went
 * tells the bearing of the stronghold; an eye that dives into the ground is above it. Start it just before the throw
 * and tick it every tick afterwards.
 */
public final class EyeWatcher {
    public enum State { WAITING, READ, NONE }

    /** Ticks the eye gets to appear after the throw. */
    static final int APPEAR_TICKS = 5;
    /** Samples taken before the flight is read. */
    static final int SAMPLES = 30;
    static final double NEAR = 4.0;
    /** The third sample is the first one past the launch jitter; the bearing is measured from there. */
    private static final int FROM = 2;
    private static final double SINK = 0.5;

    private final Set<UUID> before;
    private final List<Vec3> samples = new ArrayList<>();
    private EyeOfEnder eye;
    private Vec3 thrower;
    private Vec3 last;
    private int waited;
    private EyeReadings.Reading reading;
    private Vec3 readAt;
    private State state = State.WAITING;

    private EyeWatcher(Set<UUID> before) {
        this.before = before;
    }

    /** Eyes already flying near the bot, to leave out when the new one appears. */
    public static Set<UUID> nearbyEyes(AiPlayer p) {
        Set<UUID> out = new java.util.HashSet<>();
        for (EyeOfEnder e : p.level().getEntitiesOfClass(EyeOfEnder.class, p.getBoundingBox().inflate(32))) out.add(e.getUUID());
        return out;
    }

    public static EyeWatcher start(AiPlayer p, Set<UUID> eyesBefore) {
        EyeWatcher w = new EyeWatcher(Set.copyOf(eyesBefore));
        w.thrower = p.position();
        return w;
    }

    public State tick(AiPlayer p) {
        if (state == State.NONE) return state;
        if (eye == null) {
            Vec3 eyes = p.getEyePosition();
            AABB box = new AABB(eyes, eyes).inflate(NEAR);
            Optional<EyeOfEnder> found = p.level().getEntitiesOfClass(EyeOfEnder.class, box,
                    e -> !before.contains(e.getUUID()) && e.position().distanceTo(eyes) <= NEAR).stream().findFirst();
            if (found.isEmpty()) {
                if (++waited > APPEAR_TICKS) state = State.NONE;
                return state;
            }
            eye = found.get();
        }
        if (!eye.isRemoved()) {
            last = eye.position();
            if (samples.size() < SAMPLES) samples.add(last);
        }
        if (reading == null && (samples.size() >= SAMPLES || eye.isRemoved())) read();
        return state;
    }

    private void read() {
        if (samples.size() <= FROM) {
            state = State.NONE; // gone before it flew anywhere
            return;
        }
        Vec3 from = samples.get(FROM), to = samples.get(samples.size() - 1);
        boolean sank = to.y < from.y - SINK;
        double bearing = Triangulate.bearing(to.x - from.x, to.z - from.z);
        double x = sank ? to.x : thrower.x, z = sank ? to.z : thrower.z;
        reading = new EyeReadings.Reading(x, z, bearing, sank, eye.level().getGameTime());
        readAt = to;
        state = State.READ;
    }

    /** The reading, once {@link #tick} has returned READ. */
    public EyeReadings.Reading reading() {
        return reading;
    }

    /** Where the eye was when it was read (for a sunk eye: where it was going down). */
    public Vec3 readPosition() {
        return readAt;
    }

    /** The eye's last known position: where a surviving eye drops. Null before it appeared. */
    public Optional<Vec3> dropPos() {
        return Optional.ofNullable(last);
    }

    /** The eye has dropped as an item or shattered. */
    public boolean eyeGone() {
        return eye != null && eye.isRemoved();
    }
}
