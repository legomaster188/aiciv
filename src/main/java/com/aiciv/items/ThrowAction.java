package com.aiciv.items;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.AttackAction;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.Ballistics;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemUse;
import com.aiciv.world.Sight;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Throws a snowball, egg, potion or bottle o' enchanting at a block's top or at a mob or player, arcing as vanilla does. */
public final class ThrowAction implements Action {
    static final double SEARCH_RADIUS = 32;
    private static final String THROWABLE_LIST = "snowball, egg, splash_potion, lingering_potion, experience_bottle";

    private final String raw;
    private final BlockPos at;
    private final String targetName;
    private String done;

    public ThrowAction(String item, BlockPos atOrNull, String targetOrNull) {
        this.raw = item;
        this.at = atOrNull;
        this.targetName = targetOrNull;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        Potions.ThrowSpec spec = Potions.throwSpec(raw);
        if (!Potions.isThrowable(spec.item())) {
            return ActionStatus.failed(raw + " can't be thrown; throw works for " + THROWABLE_LIST);
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(spec.item()));
        boolean potion = item == Items.SPLASH_POTION || item == Items.LINGERING_POTION;
        Predicate<ItemStack> match = s -> DrinkAction.isPotionOf(s, item, potion ? spec.potion() : null);
        if (InventoryOps.count(p, match) == 0) {
            return ActionStatus.failed("no " + (spec.potion() != null ? spec.item() + " of " + spec.potion() : spec.item()) + " to throw");
        }

        Vec3 point;
        String what;
        BlockPos where;
        if (at != null) {
            where = at;
            point = new Vec3(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
            what = "the point";
            if (!Sight.canSee(p, at)) return ActionStatus.failed("can't see " + what + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
        } else {
            LivingEntity e = AttackAction.findTarget(p, targetName, SEARCH_RADIUS);
            if (e == null) return ActionStatus.failed("no " + targetName + " within " + (int) SEARCH_RADIUS + " blocks");
            where = e.blockPosition();
            point = e.getBoundingBox().getCenter();
            what = targetName;
            if (!p.hasLineOfSight(e)) return ActionStatus.failed("can't see " + what + " at " + where.getX() + " " + where.getY() + " " + where.getZ());
        }
        String coords = where.getX() + " " + where.getY() + " " + where.getZ();
        Ballistics.Kind kind = potion ? Ballistics.Kind.POTION : Ballistics.Kind.THROWN;
        Optional<float[]> aim = ItemUse.aimThrow(p, kind, point);
        if (aim.isEmpty()) {
            double dy = point.y - p.getEyeY();
            return ActionStatus.failed(what + " at " + coords + " is out of range; "
                    + (potion ? "potions" : "that") + " reaches about " + (int) Ballistics.maxRange(kind, kind.speed(), dy) + " blocks");
        }
        ItemUse.select(p, match);
        InteractionResult r = ItemUse.useFacing(p, aim.get()[0], aim.get()[1]);
        if (!r.consumesAction()) return ActionStatus.failed("nothing was thrown");
        done = "threw a " + spec.item().replace('_', ' ') + " at " + coords;
        return ActionStatus.DONE;
    }

    @Override
    public String describe() {
        return done != null ? done : "throw " + raw;
    }

    @Override
    public int timeoutTicks() {
        return 60;
    }
}
