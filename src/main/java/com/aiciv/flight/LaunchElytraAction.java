package com.aiciv.flight;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.EquipAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Takes off like a player: jump, and open the wings on the way down (or with a rocket in hand). Puts a carried elytra
 * on first. Done once the bot is gliding.
 */
public final class LaunchElytraAction implements Action {
    static final int TAKEOFF_TICKS = 30;
    /** Below this much durability left the elytra is as good as broken (it stops working at 1). */
    static final int MIN_DURABILITY = 10;
    static final float ROCKET_PITCH = -30f;

    private boolean checked;
    private boolean jumped;
    private int ticks;
    private boolean boosted;

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p.isFallFlying()) return ActionStatus.DONE;
        if (!checked) {
            ActionStatus problem = check(ctx, p);
            if (problem != null) return problem;
            checked = true;
        }
        ticks++;
        if (ticks > TAKEOFF_TICKS) {
            return ActionStatus.failed("could not take off (jump from 3+ blocks up or carry firework rockets)");
        }
        if (p.onGround() && !jumped) {
            p.jumpFromGround();
            jumped = true;
            return ActionStatus.RUNNING;
        }
        if (!p.onGround() && p.getDeltaMovement().y < 0 && p.tryToStartFallFlying()) {
            if (!boosted && ItemUse.select(p, s -> s.is(Items.FIREWORK_ROCKET))) {
                boosted = ItemUse.useFacing(p, p.getYRot(), ROCKET_PITCH).consumesAction();
            }
            return ActionStatus.DONE;
        }
        return ActionStatus.RUNNING;
    }

    /** Wears a carried elytra and checks that it still works; null when the bot is ready to jump. */
    private static ActionStatus check(BotContext ctx, AiPlayer p) {
        if (!LivingEntity.canGlideUsing(p.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)) {
            if (InventoryOps.count(p, s -> s.is(Items.ELYTRA)) == 0) {
                return p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) ? broken() : ActionStatus.failed("no elytra");
            }
            new EquipAction("elytra").tick(ctx);
        }
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return ActionStatus.failed("no elytra");
        if (chest.getMaxDamage() - chest.getDamageValue() <= MIN_DURABILITY) return broken();
        return null;
    }

    private static ActionStatus broken() {
        return ActionStatus.failed("the elytra is nearly broken");
    }

    @Override
    public String describe() {
        return "launch elytra";
    }

    @Override
    public int timeoutTicks() {
        return 60;
    }
}
