package com.aiciv.reflex;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.bot.Bot;
import com.aiciv.perception.GameTime;
import com.aiciv.reflex.ReflexPolicy.Reflex;
import com.aiciv.reflex.ReflexPolicy.Snapshot;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * One bot's automatic reactions. Each tick it asks {@link ReflexPolicy} what to do; when a reflex starts it
 * pauses the bot's actions, runs its own short behaviour, and when that ends it lets the actions carry on.
 * Server thread only, ticked before the navigator and the executor.
 */
public final class ReflexController {
    /** "Recently attacked" means hurt by a mob this many ticks ago or less. */
    static final int ATTACK_MEMORY_TICKS = 5 * 20;
    static final double ATTACKER_RANGE = 16;
    /** After a reflex fails or times out it is switched off for this long, so it cannot loop. */
    static final int RETRY_TICKS = 5 * 20;
    /** Cancelled reflexes that barely started are not worth telling the brain about. */
    static final int NOTE_AFTER_TICKS = 10;
    /** The same note is not repeated within this window (a long flight is several legs). */
    static final int NOTE_REPEAT_TICKS = 10 * 20;

    private Reflex active = Reflex.NONE;
    private Action behaviour;
    private LivingEntity cause;
    private int startedAt;
    private int ticksRunning;
    private final Map<Reflex, Integer> blockedUntil = new EnumMap<>(Reflex.class);
    private String lastNote = "";
    private int lastNoteAt = Integer.MIN_VALUE / 2;

    public Reflex active() {
        return active;
    }

    public void tick(Bot b) {
        AiPlayer p = b.player;
        if (p.isDeadOrDying()) return;
        BotContext ctx = b.context;
        LivingEntity attacker = recentAttacker(p);
        Reflex wanted = decide(p, attacker);

        // Health regenerates while running, which would end the flight after a second; finish it unless
        // something more urgent (lava, drowning) comes up.
        boolean committed = active == Reflex.FLEE && (wanted == Reflex.NONE || wanted.ordinal() > Reflex.FLEE.ordinal());
        if (active != Reflex.NONE && wanted != active && !committed) end(b, "preempted");
        if (active != Reflex.NONE) {
            ticksRunning++;
            ActionStatus status = tickBehaviour(ctx);
            if (status.state() != ActionStatus.State.RUNNING || ticksRunning >= behaviour.timeoutTicks()) {
                boolean failed = status.state() != ActionStatus.State.DONE;
                if (failed) blockedUntil.put(active, p.tickCount + RETRY_TICKS);
                end(b, failed ? (status.state() == ActionStatus.State.FAILED ? status.reason() : "timed out") : null);
                attacker = recentAttacker(p);
                wanted = decide(p, attacker);
            }
        }
        if (active == Reflex.NONE && wanted != Reflex.NONE) start(b, wanted, attacker);
        if (active == Reflex.NONE && b.executor.paused()) b.executor.resume(ctx);
    }

    /** Drops any reflex in progress and lets the actions run again, e.g. when the bot is respawned. */
    public void reset(Bot b) {
        if (active != Reflex.NONE) {
            cancelBehaviour(b.context);
            active = Reflex.NONE;
            behaviour = null;
            cause = null;
        }
        if (b.executor.paused()) b.executor.resume(b.context);
    }

    // ---------------------------------------------------------------- deciding

    private Reflex decide(AiPlayer p, LivingEntity attacker) {
        return ReflexPolicy.decide(mask(snapshot(p, attacker), p.tickCount));
    }

    private Snapshot snapshot(AiPlayer p, LivingEntity attacker) {
        boolean hazard = HazardEscape.inHazardBlock(p) || (p.isOnFire() && !p.isInWater() && HazardEscape.waterNearby(p));
        // Air only matters while the head is under water; it refills in a few ticks once the bot surfaces.
        int air = p.isUnderWater() ? p.getAirSupply() : p.getMaxAirSupply();
        boolean busyEating = p.isUsingItem() && active != Reflex.EAT;
        return new Snapshot(hazard, air, p.getMaxAirSupply(), p.getHealth(), p.getFoodData().getFoodLevel(),
                ReflexEat.hasFood(p), attacker != null, armed(p), busyEating);
    }

    /** A reflex that failed recently is hidden from the policy so the next best one (or none) is chosen. */
    private Snapshot mask(Snapshot s, int now) {
        boolean escape = s.inLavaOrFire() && !blocked(Reflex.ESCAPE_HAZARD, now);
        int air = blocked(Reflex.SURFACE, now) ? s.maxAir() : s.air();
        // Cornered: if running away does not work, fight back with whatever is in hand.
        float health = blocked(Reflex.FLEE, now) ? Math.max(s.health(), ReflexPolicy.FLEE_HEALTH + 1) : s.health();
        boolean armed = s.holdingWeaponOrTool() && !blocked(Reflex.FIGHT_BACK, now);
        boolean food = s.hasFood() && !blocked(Reflex.EAT, now);
        return new Snapshot(escape, air, s.maxAir(), health, s.food(), food, s.recentlyAttackedByMob(), armed, s.busyEating());
    }

    private boolean blocked(Reflex r, int now) {
        return blockedUntil.getOrDefault(r, Integer.MIN_VALUE) > now;
    }

    /** The bot carries something that hits harder than a fist: any weapon or tool. */
    private static boolean armed(AiPlayer p) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && (s.has(DataComponents.WEAPON) || s.has(DataComponents.TOOL))) return true;
        }
        return false;
    }

    /** The mob that hurt the bot in the last five seconds, if it is still alive and within 16 blocks. */
    private static LivingEntity recentAttacker(AiPlayer p) {
        LivingEntity hurter = p.getLastHurtByMob();
        if (!(hurter instanceof Mob mob) || !mob.isAlive() || mob.level() != p.level()) return null;
        if (p.tickCount - p.getLastHurtByMobTimestamp() > ATTACK_MEMORY_TICKS) return null;
        return mob.distanceTo(p) <= ATTACKER_RANGE ? mob : null;
    }

    // ---------------------------------------------------------------- running

    private void start(Bot b, Reflex reflex, LivingEntity attacker) {
        AiPlayer p = b.player;
        BotContext ctx = b.context;
        b.executor.pause(ctx);
        ctx.navigator().stop();
        if (p.isUsingItem() && reflex != Reflex.EAT) p.stopUsingItem();
        cause = attacker;
        behaviour = switch (reflex) {
            case ESCAPE_HAZARD -> new HazardEscape();
            case SURFACE -> new SurfaceAction();
            case FLEE -> new FleeAction(attacker);
            case FIGHT_BACK -> AttackAction.on(attacker);
            case EAT -> new ReflexEat();
            case NONE -> throw new IllegalStateException("no reflex to start");
        };
        active = reflex;
        startedAt = p.tickCount;
        ticksRunning = 0;
    }

    private ActionStatus tickBehaviour(BotContext ctx) {
        try {
            return behaviour.tick(ctx);
        } catch (RuntimeException e) {
            return ActionStatus.failed(e.getClass().getSimpleName());
        }
    }

    private void cancelBehaviour(BotContext ctx) {
        try {
            behaviour.cancel(ctx);
        } catch (RuntimeException ignored) {
            // The reflex is over either way.
        }
        ctx.player().stopMoving();
    }

    /** Ends the active reflex. {@code problem} is why it ended badly, "preempted" if the policy changed its mind, or null if it finished. */
    private void end(Bot b, String problem) {
        AiPlayer p = b.player;
        Reflex reflex = active;
        Action finished = behaviour;
        LivingEntity target = cause;
        int ran = p.tickCount - startedAt;
        cancelBehaviour(b.context);
        active = Reflex.NONE;
        behaviour = null;
        cause = null;
        if (problem == null || ran >= NOTE_AFTER_TICKS) note(b, describe(reflex, finished, target, problem));
    }

    private static String describe(Reflex reflex, Action finished, LivingEntity target, String problem) {
        String who = target == null ? "an attacker" : BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
        boolean ok = problem == null || problem.equals("preempted");
        return "reflex: " + switch (reflex) {
            case ESCAPE_HAZARD -> ok ? "got out of lava or fire" : "could not get out of lava or fire (" + problem + ")";
            case SURFACE -> ok ? "swam up for air" : "could not swim up for air (" + problem + ")";
            case FLEE -> ok ? "fled from " + who : "could not flee from " + who + " (" + problem + ")";
            case FIGHT_BACK -> (ok ? "fought " + who + (target != null && !target.isAlive() ? " and killed it" : "")
                    : "could not fight " + who + " (" + problem + ")");
            case EAT -> ok && finished instanceof ReflexEat eat && eat.ate() != null ? "ate " + eat.ate()
                    : "could not eat (" + problem + ")";
            case NONE -> "none";
        };
    }

    /** Tells the brain what happened without waking it. */
    private void note(Bot b, String text) {
        int now = b.player.tickCount;
        if (text.equals(lastNote) && now - lastNoteAt < NOTE_REPEAT_TICKS) return;
        lastNote = text;
        lastNoteAt = now;
        long clock = b.player.level().getServer().overworld().getOverworldClockTime();
        b.brain.note(GameTime.stamp(clock), text);
    }
}
