package com.aiciv.nether;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.combat.BowShot;
import com.aiciv.dimension.Portals;
import com.aiciv.dimension.Waypoints;
import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fights blazes until the bot has {@code count} more blaze rods: arrows from beyond six blocks when it has a bow, the best
 * weapon up close, then picks up what they dropped. Remembers a blaze spawner it sees and waits near it for more.
 */
public final class HuntBlazesAction implements Action {
    static final double SIGHT = 32;
    static final int SPAWNER_SIGHT = 24;
    static final double BOW_RANGE = 6;
    static final int BOW_CHARGE = 20;
    static final int LOOT_RADIUS = 8;
    static final int LOOT_TICKS = 100;
    /** Ticks without a kill, a hit or an arrow before the bot gives up. */
    static final int QUIET_LIMIT = 600;
    static final int MIN_HEALTH = 8;
    static final double WAIT_RADIUS = 6;
    static final double CLOSE_IN = 1.5;
    static final double HOVER_HEIGHT = 2.5;
    static final int SKIP_TICKS = 200;
    static final int SPAWNER_EVERY = 20;
    static final int BOW_RETRY_TICKS = 100;
    static final int REPLAN_TICKS = 10;
    static final int MAX_NAV_FAILURES = 3;
    static final double REPLAN_DISTANCE = 1.5;

    private final int count;
    private final boolean anyDimension;
    private boolean started;
    private int startRods;
    private int ticks;
    private int quiet;
    private boolean sawBlaze;
    private Blaze current;
    private float currentHealth;
    private boolean equipped;
    private BowShot shot;
    private long noBowUntil;
    private ItemGatherer loot;
    private int lootTicks;
    private final Map<Integer, Long> skipped = new HashMap<>();
    private boolean walkingToSpawner;
    private int sinceWalk;
    private Vec3 anchor;
    private int sinceGoTo;
    private int navFailures;
    private boolean failureCounted;
    private String summary;

    public HuntBlazesAction(int count) {
        this(count, false);
    }

    /** {@code anyDimension} lets game tests run it in the overworld. */
    public HuntBlazesAction(int count, boolean anyDimension) {
        this.count = count;
        this.anyDimension = anyDimension;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!started) {
            started = true;
            String wrong = anyDimension ? null : Refusal.notInNether(ctx, "hunt_blazes");
            if (wrong != null) return ActionStatus.failed(wrong);
            startRods = rods(p);
        }
        int gained = rods(p) - startRods;
        if (gained >= count) {
            stopAll(ctx);
            summary = "got " + gained + " blaze rod" + (gained == 1 ? "" : "s");
            return ActionStatus.DONE;
        }
        if (p.getHealth() <= MIN_HEALTH) {
            stopAll(ctx);
            return ActionStatus.failed("too hurt to keep fighting blazes (health " + (int) Math.ceil(p.getHealth()) + ")");
        }
        long now = p.level().getGameTime();
        if (ticks++ % SPAWNER_EVERY == 0) noteSpawner(ctx, p, now);
        if (loot != null) {
            if (++lootTicks <= LOOT_TICKS && loot.tick(ctx)) return ActionStatus.RUNNING;
            loot.stop(ctx);
            loot = null;
        }
        if (current != null && !current.isAlive()) {
            stopFighting(ctx, p);
            current = null;
            quiet = 0;
            startLoot();
            return ActionStatus.RUNNING;
        }
        Blaze target = pickTarget(p, now);
        if (target == null) return idle(ctx, p, gained);
        sawBlaze = true;
        walkingToSpawner = false;
        if (target != current) {
            stopFighting(ctx, p);
            current = target;
            currentHealth = target.getHealth();
        } else if (target.getHealth() < currentHealth) {
            currentHealth = target.getHealth();
            quiet = 0;
        }
        if (++quiet >= QUIET_LIMIT) return giveUp(ctx, gained);
        return fight(ctx, p, target, now);
    }

    private static int rods(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(Items.BLAZE_ROD));
    }

    private Blaze pickTarget(AiPlayer p, long now) {
        if (current != null && current.isAlive() && p.distanceTo(current) <= SIGHT && skipped.getOrDefault(current.getId(), 0L) <= now) {
            return current;
        }
        Optional<Blaze> best = Finder.nearestEntity(p, Blaze.class,
                b -> p.hasLineOfSight(b) && skipped.getOrDefault(b.getId(), 0L) <= now, SIGHT);
        return best.orElse(null);
    }

    private ActionStatus fight(BotContext ctx, AiPlayer p, Blaze blaze, long now) {
        boolean bow = InventoryOps.has(p, Items.BOW, 1) && InventoryOps.count(p, s -> s.is(ItemTags.ARROWS)) > 0 && now >= noBowUntil;
        if (bow && p.distanceTo(blaze) > BOW_RANGE) {
            if (anchor != null) {
                ctx.navigator().stop();
                anchor = null;
            }
            if (shot == null) shot = new BowShot(() -> blaze.getBoundingBox().getCenter(), BOW_CHARGE);
            BowShot.State s = shot.tick(p);
            if (s == BowShot.State.FAILED) {
                shot = null;
                noBowUntil = now + BOW_RETRY_TICKS;
            } else if (s == BowShot.State.RELEASED) {
                shot = null;
                quiet = 0;
            }
            return ActionStatus.RUNNING;
        }
        if (shot != null) {
            shot.cancel(p);
            shot = null;
        }
        return melee(ctx, p, blaze, now);
    }

    /** Walks up to the blaze and hits it with the best weapon whenever the weapon reaches it. */
    private ActionStatus melee(BotContext ctx, AiPlayer p, Blaze blaze, long now) {
        if (!equipped) {
            equipped = true;
            InventoryOps.selectBest(p, AttackAction::weaponScore);
        }
        if (p.isWithinAttackRange(p.getMainHandItem(), blaze.getBoundingBox(), 0.0) && p.hasLineOfSight(blaze)) {
            if (anchor != null) {
                ctx.navigator().stop();
                anchor = null;
            }
            if (p.getAttackStrengthScale(0.5F) >= 0.9F) {
                p.lookAt(EntityAnchorArgument.Anchor.EYES, blaze.getEyePosition());
                p.attack(blaze);
                p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        sinceGoTo++;
        if (anchor != null && nav.state() == NavStatus.State.FAILED && !failureCounted) {
            failureCounted = true;
            if (++navFailures >= MAX_NAV_FAILURES) {
                skipped.put(blaze.getId(), now + SKIP_TICKS);
                stopFighting(ctx, p);
                current = null;
                return ActionStatus.RUNNING;
            }
        }
        boolean moved = anchor != null && anchor.distanceTo(blaze.position()) > REPLAN_DISTANCE;
        boolean idle = nav.state() != NavStatus.State.RUNNING;
        if (anchor == null || moved || (idle && sinceGoTo >= REPLAN_TICKS)) {
            anchor = blaze.position();
            sinceGoTo = 0;
            failureCounted = false;
            // One that hovers above reach comes down slowly after a hit: wait right under it.
            boolean high = anchor.y - p.getY() > HOVER_HEIGHT;
            ctx.navigator().goTo(high ? new ColumnGoal(anchor.x, anchor.z, CLOSE_IN) : new NearGoal(anchor.x, anchor.y, anchor.z, CLOSE_IN));
        }
        return ActionStatus.RUNNING;
    }

    private void startLoot() {
        loot = new ItemGatherer(c -> {
            AiPlayer p = c.player();
            return p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(LOOT_RADIUS), e -> e.getItem().is(Items.BLAZE_ROD));
        });
        lootTicks = 0;
    }

    private ActionStatus giveUp(BotContext ctx, int gained) {
        stopAll(ctx);
        if (gained > 0) {
            summary = "got " + gained + " of " + count + " blaze rods; no more blazes came";
            return ActionStatus.DONE;
        }
        return ActionStatus.failed(sawBlaze ? "could not hit any blaze" : "no blazes in sight");
    }

    /** Nobody to fight: wait near a remembered spawner for blazes to come, and give up after a quiet spell. */
    private ActionStatus idle(BotContext ctx, AiPlayer p, int gained) {
        stopFighting(ctx, p);
        current = null;
        Optional<Waypoints.Waypoint> spawner = ctx.attachments() == null ? Optional.empty()
                : ctx.attachments().get(Waypoints.class, Waypoints::new).nearest(Waypoints.BLAZE_SPAWNER, Portals.key(p.level()),
                        new Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        boolean walking = false;
        if (spawner.isPresent()) {
            Pos at = spawner.get().pos();
            double dx = at.x() + 0.5 - p.getX(), dy = at.y() - p.getY(), dz = at.z() + 0.5 - p.getZ();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= WAIT_RADIUS) {
                if (walkingToSpawner) ctx.navigator().stop();
                walkingToSpawner = false;
            } else {
                walking = true;
                sinceWalk++;
                boolean running = ctx.navigator().status().state() == NavStatus.State.RUNNING;
                if (!walkingToSpawner || (!running && sinceWalk >= 20)) {
                    walkingToSpawner = true;
                    sinceWalk = 0;
                    ctx.navigator().goTo(new NearGoal(at.x() + 0.5, at.y(), at.z() + 0.5, WAIT_RADIUS - 1));
                }
            }
        }
        // The walk to a far spawner does not count as a quiet spell.
        if (!walking && ++quiet >= QUIET_LIMIT) return giveUp(ctx, gained);
        return ActionStatus.RUNNING;
    }

    /** A visible spawner whose spinning mob is a blaze becomes a waypoint. */
    private void noteSpawner(BotContext ctx, AiPlayer p, long now) {
        if (ctx.attachments() == null) return;
        for (BlockPos pos : Finder.visibleStates(p, s -> s.is(Blocks.SPAWNER), SPAWNER_SIGHT)) {
            BlockEntity be = p.level().getBlockEntity(pos);
            if (!(be instanceof SpawnerBlockEntity spawner)) continue;
            Entity shown = spawner.getSpawner().getOrCreateDisplayEntity(p.level(), pos);
            if (shown instanceof Blaze) {
                ctx.attachments().get(Waypoints.class, Waypoints::new).mark(Waypoints.BLAZE_SPAWNER, Portals.key(p.level()),
                        new Pos(pos.getX(), pos.getY(), pos.getZ()), "", now);
            }
        }
    }

    private void stopFighting(BotContext ctx, AiPlayer p) {
        if (shot != null) shot.cancel(p);
        shot = null;
        if (anchor != null) ctx.navigator().stop();
        anchor = null;
        navFailures = 0;
        failureCounted = false;
    }

    private void stopAll(BotContext ctx) {
        if (ctx.player() != null) stopFighting(ctx, ctx.player());
        if (loot != null) loot.stop(ctx);
        loot = null;
        ctx.navigator().stop();
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (shot != null && ctx.player() != null) shot.cancel(ctx.player());
        shot = null;
        anchor = null;
        equipped = false;
        walkingToSpawner = false;
    }

    @Override
    public void cancel(BotContext ctx) {
        stopAll(ctx);
    }

    @Override
    public String describe() {
        return summary != null ? summary : "hunt blazes";
    }

    @Override
    public int timeoutTicks() {
        return 6000 + 1200 * count;
    }
}
