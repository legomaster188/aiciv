package com.aiciv.flight;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.ItemUse;
import net.minecraft.world.item.Items;

/** Fires one firework rocket while gliding, for a burst of speed. */
public final class BoostAction implements Action {
    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (!p.isFallFlying() || !ItemUse.select(p, s -> s.is(Items.FIREWORK_ROCKET))) {
            return ActionStatus.failed("boost needs you gliding with a firework_rocket");
        }
        return ItemUse.useFacing(p, p.getYRot(), p.getXRot()).consumesAction()
                ? ActionStatus.DONE
                : ActionStatus.failed("boost needs you gliding with a firework_rocket");
    }

    @Override
    public String describe() {
        return "boost";
    }

    @Override
    public int timeoutTicks() {
        return 5;
    }
}
