package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.InventoryOps;
import java.util.Locale;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hunts one player or mob with the best weapon the bot holds: walk up, then hit whenever the attack cooldown
 * is ready and the target is inside a survival player's reach and sight.
 */
public final class AttackAction implements Action {
    static final double SEARCH_RADIUS = 16;
    static final int GOT_AWAY_TICKS = 3 * 20;
    static final double REPLAN_DISTANCE = 2.0;
    static final int REPLAN_TICKS = 10;
    static final int MAX_NAV_FAILURES = 3;

    private final String label;
    private LivingEntity target;
    private boolean equipped;
    private int awayTicks;
    private Vec3 anchor;
    private int sinceGoTo;
    private int navFailures;
    private boolean failureCounted;

    /** Attacks the nearest player called this or mob of this type (id, with or without minecraft:, singular or plural). */
    public AttackAction(String target) {
        this.label = PlayerNames.clean(target);
    }

    /** Attacks one given creature, for reflexes that already know who hit the bot. */
    public static AttackAction on(LivingEntity target) {
        AttackAction a = new AttackAction(typeName(target));
        a.target = target;
        return a;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer me = ctx.player();
        if (target == null) {
            target = resolve(me);
            if (target == null) return ActionStatus.failed("no " + label + " nearby");
        }
        if (!target.isAlive()) return ActionStatus.DONE;
        if (target.level() != me.level() || me.distanceTo(target) > SEARCH_RADIUS) {
            if (++awayTicks >= GOT_AWAY_TICKS) return ActionStatus.failed(label + " got away");
        } else {
            awayTicks = 0;
        }
        if (!equipped) {
            equipped = true;
            InventoryOps.selectBest(me, AttackAction::weaponScore);
        }
        if (me.isWithinEntityInteractionRange(target, 0.0) && me.hasLineOfSight(target)) {
            if (anchor != null) {
                ctx.navigator().stop();
                anchor = null;
            }
            if (me.getAttackStrengthScale(0.5F) >= 0.9F) {
                me.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
                me.attack(target);
                me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
            return ActionStatus.RUNNING;
        }
        return approach(ctx);
    }

    private ActionStatus approach(BotContext ctx) {
        NavStatus s = ctx.navigator().status();
        sinceGoTo++;
        if (anchor != null && s.state() == NavStatus.State.FAILED && !failureCounted) {
            failureCounted = true;
            if (++navFailures >= MAX_NAV_FAILURES) return ActionStatus.failed("can't reach " + label + ": " + s.reason());
        }
        boolean moved = anchor != null && anchor.distanceTo(target.position()) > REPLAN_DISTANCE;
        boolean idle = s.state() != NavStatus.State.RUNNING;
        if (anchor == null || moved || (idle && sinceGoTo >= REPLAN_TICKS)) {
            anchor = target.position();
            sinceGoTo = 0;
            failureCounted = false;
            ctx.navigator().goTo(new NearGoal(anchor.x, anchor.y, anchor.z, REPLAN_DISTANCE));
        }
        return ActionStatus.RUNNING;
    }

    @Override
    public void interrupted(BotContext ctx) {
        anchor = null;
        equipped = false;
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
    }

    @Override
    public String describe() {
        return "attack " + label;
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }

    // ---------------------------------------------------------------- finding the target

    private LivingEntity resolve(AiPlayer me) {
        ServerPlayer player = me.level().getServer().getPlayerList().getPlayerByName(label);
        if (player != null && player != me && player.level() == me.level() && !player.isSpectator() && player.isAlive()
                && me.distanceTo(player) <= SEARCH_RADIUS) {
            return player;
        }
        EntityType<?> type = entityType(label);
        if (type == null) return null;
        AABB box = me.getBoundingBox().inflate(SEARCH_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity e : me.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == me || e.getType() != type || !e.isAlive()) continue;
            double d = e.distanceToSqr(me);
            if (d <= SEARCH_RADIUS * SEARCH_RADIUS && d < bestDistance) {
                bestDistance = d;
                best = e;
            }
        }
        return best;
    }

    /** "zombie", "minecraft:zombie", "Zombies", "wolves" and "cave spider" all find their entity type, or null. */
    static EntityType<?> entityType(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        String[] forms = {q, strip(q, "s", ""), strip(q, "es", ""), strip(q, "ves", "f")};
        for (String form : forms) {
            if (form == null) continue;
            Identifier id = Identifier.tryParse("minecraft:" + form);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return BuiltInRegistries.ENTITY_TYPE.getValue(id);
        }
        return null;
    }

    private static String strip(String s, String suffix, String replacement) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) + replacement : null;
    }

    private static String typeName(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    // ---------------------------------------------------------------- weapons

    /** Swords, then other weapons, then axes, then any tool, each by attack damage; hands score nothing. */
    static double weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        double damage = attackDamage(s);
        if (s.is(ItemTags.SWORDS)) return 1000 + damage;
        if (s.has(DataComponents.WEAPON)) return 500 + damage;
        if (s.is(ItemTags.AXES)) return 300 + damage;
        if (s.has(DataComponents.TOOL)) return 100 + damage;
        return 0;
    }

    static double attackDamage(ItemStack s) {
        double[] sum = {0};
        s.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum[0] += modifier.amount();
            }
        });
        return sum[0];
    }
}
