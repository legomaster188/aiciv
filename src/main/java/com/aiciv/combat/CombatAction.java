package com.aiciv.combat;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.combat.CombatMove.*;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * Runs a short chain of exact fighting moves, one per tick step, the way a player would press the keys: hit once,
 * sidestep, back away, jump and hit on the way down, raise a shield, draw a bow, pick a hotbar slot.
 */
public final class CombatAction implements Action {
    static final double SEARCH_RADIUS = 16;
    static final double BOW_RANGE = 32;
    static final int APPROACH_TICKS = 40;
    static final int COOLDOWN_WAIT_TICKS = 20;
    static final float READY = 0.9F;
    static final double REPLAN_DISTANCE = 2.0;
    static final int REPLAN_TICKS = 10;
    static final double CLOSE = 2.0;

    private final List<CombatMove> moves;
    private int index;
    private int moveTick; // ticks spent in the current move
    private LivingEntity current;
    private String currentLabel;
    private boolean manualHotbar;

    // state of the current move, cleared by resetMove()
    private boolean begun;
    private boolean reached;
    private boolean armed;
    private int approachTicks;
    private Vec3 anchor;
    private int sinceGoTo;
    private int jumpedAt = -100;

    public CombatAction(List<CombatMove> moves) {
        this.moves = List.copyOf(moves);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (index >= moves.size()) return ActionStatus.DONE;
        AiPlayer me = ctx.player();
        CombatMove move = moves.get(index);
        if (!begun) {
            String label = targetOf(move);
            if (label != null && (current == null || !label.equals(currentLabel))) {
                current = AttackAction.findTarget(me, label, SEARCH_RADIUS);
                currentLabel = label;
                if (current == null) return ActionStatus.failed("no " + label + " nearby");
            }
        }
        if (current != null && !current.isAlive()) {
            return new ActionStatus(ActionStatus.State.DONE, currentLabel + " is dead");
        }
        ActionStatus status = step(ctx, me, move);
        if (status != null) return status;
        moveTick++;
        return ActionStatus.RUNNING;
    }

    /** Null while the move is still running; RUNNING when it just finished and the next one starts on the next tick. */
    private ActionStatus step(BotContext ctx, AiPlayer me, CombatMove move) {
        boolean first = !begun;
        begun = true;
        ActionStatus done = switch (move) {
            case AttackOnce a -> attackOnce(ctx, me, a.target());
            case JumpCrit j -> jumpCrit(ctx, me, j.target());
            case Strafe s -> strafe(me, s);
            case BackOff b -> backOff(me, b);
            case Shield s -> shield(me, s, first);
            case Bow b -> bow(me, b, first);
            case Hotbar h -> {
                me.getInventory().setSelectedSlot(h.slot() - 1);
                manualHotbar = true;
                yield ActionStatus.DONE;
            }
        };
        if (done == null) return null;
        if (done.state() == ActionStatus.State.FAILED) return done;
        advance();
        return index >= moves.size() ? ActionStatus.DONE : ActionStatus.RUNNING;
    }

    private void advance() {
        index++;
        moveTick = 0;
        resetMove();
    }

    private void resetMove() {
        begun = false;
        reached = false;
        armed = false;
        approachTicks = 0;
        anchor = null;
        sinceGoTo = 0;
        jumpedAt = -100;
    }

    // ---------------------------------------------------------------- moves

    private ActionStatus attackOnce(BotContext ctx, AiPlayer me, String label) {
        if (!reached) {
            ActionStatus walk = reachTarget(ctx, me, "attack_once", label);
            if (walk.state() != ActionStatus.State.DONE) return walk.state() == ActionStatus.State.FAILED ? walk : null;
            reached = true;
            moveTick = 0; // the cooldown wait is counted from here
            selectWeapon(me);
        }
        boolean ready = me.getAttackStrengthScale(0.5F) >= READY;
        if (!ready && moveTick < COOLDOWN_WAIT_TICKS) {
            face(me, current);
            return null;
        }
        face(me, current);
        me.attack(current);
        me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return ActionStatus.DONE;
    }

    private ActionStatus jumpCrit(BotContext ctx, AiPlayer me, String label) {
        if (!reached) {
            ActionStatus walk = reachTarget(ctx, me, "jump_crit", label);
            if (walk.state() != ActionStatus.State.DONE) return walk.state() == ActionStatus.State.FAILED ? walk : null;
            reached = true;
            moveTick = 0; // the cooldown wait is counted from here
            selectWeapon(me);
        }
        if (!armed) {
            // A hit below full strength is weaker and not a critical one; wait out the cooldown on the ground.
            face(me, current);
            if (me.getAttackStrengthScale(0.5F) < 1.0F && moveTick < COOLDOWN_WAIT_TICKS) return null;
            armed = true;
            moveTick = 0; // 30 ticks to land the hit are counted from here
        }
        if (moveTick >= 30) {
            return ActionStatus.failed("jump_crit: could not land a falling hit");
        }
        face(me, current);
        boolean inReach = me.isWithinEntityInteractionRange(current, 0.0) && me.hasLineOfSight(current);
        if (me.onGround()) {
            if (moveTick - jumpedAt >= 6) {
                me.jumpFromGround();
                jumpedAt = moveTick;
            }
            return null;
        }
        // Vanilla only counts a critical hit once the fall has begun to add up (fallDistance > 0).
        if (inReach && me.getDeltaMovement().y < 0 && me.fallDistance > 0 && me.getAttackStrengthScale(0.5F) >= READY) {
            me.attack(current);
            me.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            return ActionStatus.DONE;
        }
        return null;
    }

    private ActionStatus strafe(AiPlayer me, Strafe s) {
        if (moveTick >= s.ticks()) {
            me.stopMoving();
            return ActionStatus.DONE;
        }
        face(me, current);
        me.xxa = s.left() ? 1.0F : -1.0F;
        me.zza = 0.0F;
        return null;
    }

    private ActionStatus backOff(AiPlayer me, BackOff b) {
        if (moveTick >= b.ticks()) {
            me.stopMoving();
            return ActionStatus.DONE;
        }
        face(me, current);
        me.xxa = 0.0F;
        me.zza = -1.0F;
        return null;
    }

    private ActionStatus shield(AiPlayer me, Shield s, boolean first) {
        if (first) {
            if (!me.getOffhandItem().is(Items.SHIELD)) {
                ItemStack found = findInInventory(me, Items.SHIELD);
                if (found == null) return ActionStatus.failed("no shield");
                ItemStack one = found.split(1);
                ItemStack old = me.getOffhandItem();
                me.setItemSlot(EquipmentSlot.OFFHAND, one);
                if (!old.isEmpty()) InventoryOps.addOrDrop(me, old);
            }
            me.gameMode.useItem(me, me.level(), me.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        if (moveTick >= s.ticks()) {
            me.stopUsingItem();
            return ActionStatus.DONE;
        }
        if (current != null) face(me, current);
        return null;
    }

    private ActionStatus bow(AiPlayer me, Bow b, boolean first) {
        if (first) {
            if (InventoryOps.count(me, s -> s.is(Items.BOW)) == 0) return ActionStatus.failed("no bow");
            if (InventoryOps.count(me, s -> s.is(ItemTags.ARROWS)) == 0) return ActionStatus.failed("no arrows");
            if (me.distanceTo(current) > BOW_RANGE) return ActionStatus.failed("bow: " + b.target() + " is out of range");
            InventoryOps.selectBest(me, s -> s.is(Items.BOW) ? 1 : 0);
            me.gameMode.useItem(me, me.level(), me.getMainHandItem(), InteractionHand.MAIN_HAND);
            if (!me.isUsingItem()) return ActionStatus.failed("bow: could not draw it");
        }
        if (moveTick >= b.chargeTicks()) {
            me.releaseUsingItem();
            return ActionStatus.DONE;
        }
        double dx = current.getX() - me.getX(), dz = current.getZ() - me.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        me.lookAt(EntityAnchorArgument.Anchor.EYES, current.position().add(0, current.getBbHeight() * 0.5 + 0.0045 * d * d, 0));
        return null;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Walks toward the target until it is in reach and sight: RUNNING while walking, DONE once there, FAILED after
     * {@value #APPROACH_TICKS} ticks.
     */
    private ActionStatus reachTarget(BotContext ctx, AiPlayer me, String move, String label) {
        if (inReach(me)) {
            ctx.navigator().stop();
            anchor = null;
            return ActionStatus.DONE;
        }
        if (approachTicks++ >= APPROACH_TICKS) {
            ctx.navigator().stop();
            return ActionStatus.failed(move + ": " + label + " out of reach");
        }
        sinceGoTo++;
        boolean moved = anchor != null && anchor.distanceTo(current.position()) > REPLAN_DISTANCE;
        boolean idle = ctx.navigator().status().state() != NavStatus.State.RUNNING;
        if (anchor == null || moved || (idle && sinceGoTo >= REPLAN_TICKS)) {
            anchor = current.position();
            sinceGoTo = 0;
            ctx.navigator().goTo(new NearGoal(anchor.x, anchor.y, anchor.z, CLOSE));
        }
        return ActionStatus.RUNNING;
    }

    private boolean inReach(AiPlayer me) {
        return me.isWithinEntityInteractionRange(current, 0.0) && me.hasLineOfSight(current);
    }

    private void selectWeapon(AiPlayer me) {
        if (!manualHotbar) InventoryOps.selectBest(me, AttackAction::weaponScore);
    }

    private static void face(AiPlayer me, LivingEntity target) {
        if (target != null) me.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
    }

    private static ItemStack findInInventory(AiPlayer me, net.minecraft.world.item.Item item) {
        for (ItemStack s : me.getInventory().getNonEquipmentItems()) if (!s.isEmpty() && s.is(item)) return s;
        return null;
    }

    private static String targetOf(CombatMove move) {
        return switch (move) {
            case AttackOnce a -> a.target();
            case JumpCrit j -> j.target();
            case Bow b -> b.target();
            default -> null;
        };
    }

    // ---------------------------------------------------------------- lifecycle

    private void stopEverything(BotContext ctx) {
        AiPlayer me = ctx.player();
        if (me != null) {
            me.stopMoving();
            me.stopUsingItem();
        }
        ctx.navigator().stop();
    }

    @Override
    public void cancel(BotContext ctx) {
        stopEverything(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        stopEverything(ctx);
        moveTick = 0;
        resetMove(); // the move starts over from where the reflex left the bot
    }

    @Override
    public String describe() {
        for (CombatMove m : moves) {
            String target = targetOf(m);
            if (target != null) return "combat " + moves.size() + " moves vs " + target;
        }
        return "combat " + moves.size() + " moves";
    }

    @Override
    public int timeoutTicks() {
        int sum = 200;
        for (CombatMove m : moves) sum += m.maxTicks();
        return Math.min(1200, sum);
    }
}
