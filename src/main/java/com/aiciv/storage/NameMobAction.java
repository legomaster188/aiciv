package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.EntityUse;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Names a nearby mob with a name tag that already carries the name, renaming a plain tag at an anvil first if need be. */
public final class NameMobAction implements Action {
    static final int RADIUS = 16;
    static final double REACH = 3.0;
    private static final int MAX_TRIES = 20;

    private final String targetLabel;
    private final String name;
    private RenameAction inner;
    private LivingEntity mob;
    private Approach approach;
    private int tries;
    private String doneSummary;

    public NameMobAction(String target, String name) {
        this.targetLabel = target;
        this.name = name;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (inner != null) {
            ActionStatus s = inner.tick(ctx);
            if (s.state() == ActionStatus.State.RUNNING) return s;
            inner = null;
            if (s.state() == ActionStatus.State.FAILED) return s;
        }
        if (!holdsNamedTag(p)) {
            boolean plainTag = InventoryOps.count(p, s -> s.is(Items.NAME_TAG)) > 0;
            if (plainTag && inner == null && Finder.nearestBlock(ctx, RenameAction::isAnvil, RenameAction.RADIUS).isPresent()) {
                inner = new RenameAction("name_tag", name);
                return ActionStatus.RUNNING;
            }
            return ActionStatus.failed("rename a name_tag to \"" + name + "\" at an anvil first");
        }
        if (mob == null) {
            EntityType<?> type = AttackAction.entityType(targetLabel);
            if (type == null) return ActionStatus.failed("no mob called " + targetLabel);
            mob = Finder.nearestEntity(p, LivingEntity.class, e -> !(e instanceof Player) && e.getType() == type, RADIUS).orElse(null);
            if (mob == null) return ActionStatus.failed("no " + targetLabel + " within " + RADIUS + " blocks");
            approach = Approach.entity(mob, REACH);
        }
        if (approach != null) {
            Approach.Status s = approach.tick(ctx);
            if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
            if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        }
        InventoryOps.selectBest(p, s -> isNamedTag(s) ? 1 : 0);
        BlockUse.Result r = EntityUse.interact(p, mob, InteractionHand.MAIN_HAND);
        Component custom = mob.getCustomName();
        if (custom != null && custom.getString().equals(name)) {
            doneSummary = "named the " + targetLabel + " \"" + name + "\"";
            return ActionStatus.DONE;
        }
        if (r == BlockUse.Result.NO_EFFECT) return ActionStatus.failed("the " + targetLabel + " can't be named");
        if (++tries >= MAX_TRIES) return ActionStatus.failed("could not name the " + targetLabel + ": " + r);
        approach = Approach.entity(mob, REACH - 1.0);
        return ActionStatus.RUNNING;
    }

    private boolean holdsNamedTag(AiPlayer p) {
        return InventoryOps.count(p, this::isNamedTag) > 0;
    }

    private boolean isNamedTag(ItemStack s) {
        Component custom = s.get(DataComponents.CUSTOM_NAME);
        return s.is(Items.NAME_TAG) && custom != null && custom.getString().equals(name);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (inner != null) inner.cancel(ctx);
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        if (inner != null) inner.interrupted(ctx);
        if (mob != null) approach = Approach.entity(mob, REACH);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "name a " + targetLabel + " \"" + name + "\"";
    }

    @Override
    public int timeoutTicks() {
        return 120 * 20;
    }
}
