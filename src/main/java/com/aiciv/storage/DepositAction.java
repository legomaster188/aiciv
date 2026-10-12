package com.aiciv.storage;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.ItemNames;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** Walks to a chest and moves matching inventory stacks into it with shift-clicks. */
public final class DepositAction implements Action {
    private final DepositRule rule;
    private final BlockPos given;
    private ContainerTarget target;
    private String doneSummary;

    /** @param orNull the chest to use; null means the nearest one within 16 blocks */
    public DepositAction(DepositRule rule, BlockPos orNull) {
        this.rule = rule;
        this.given = orNull;
        this.target = new ContainerTarget(orNull);
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        ContainerTarget.Step step = target.tick(ctx);
        switch (step.state()) {
            case RUNNING, OPENED:
                return ActionStatus.RUNNING;
            case FAILED:
                return ActionStatus.failed(step.reason());
            default:
        }
        AiPlayer p = ctx.player();
        Map<String, Integer> before = inventory(p);
        boolean anyToMove = false;
        if (rule.isList()) {
            for (Map.Entry<String, Integer> e : rule.listed().entrySet()) {
                Predicate<ItemStack> match = ItemNames.matcher(e.getKey());
                anyToMove |= p.getInventory().getNonEquipmentItems().stream().anyMatch(s -> !s.isEmpty() && match.test(s));
                MenuOps.moveIn(p, target.menu(), match, e.getValue());
            }
        } else {
            Predicate<ItemStack> match = s -> !rule.keeps(ItemNames.id(s), keepsByNature(s));
            anyToMove = p.getInventory().getNonEquipmentItems().stream().anyMatch(s -> !s.isEmpty() && match.test(s));
            MenuOps.moveIn(p, target.menu(), match, Integer.MAX_VALUE);
        }
        Map<String, Integer> after = inventory(p);
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int n = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (n > 0) moved.add(n + " " + e.getKey());
        }
        target.remember(ctx);
        target.close(ctx);
        if (moved.isEmpty()) {
            return ActionStatus.failed(anyToMove ? "the chest is full" : "nothing to deposit: no matching items in your inventory");
        }
        doneSummary = "deposited " + String.join(", ", moved);
        return ActionStatus.DONE;
    }

    /** Tools, weapons, armor and food stay with the bot under {@code all_except_tools}. */
    static boolean keepsByNature(ItemStack s) {
        Equippable equippable = s.get(DataComponents.EQUIPPABLE);
        return s.has(DataComponents.TOOL) || s.has(DataComponents.WEAPON) || s.has(DataComponents.FOOD) || s.isDamageableItem()
                || (equippable != null && equippable.slot().isArmor());
    }

    private static Map<String, Integer> inventory(AiPlayer p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) out.merge(ItemNames.id(s), s.getCount(), Integer::sum);
        }
        return out;
    }

    @Override
    public void cancel(BotContext ctx) {
        target.close(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        target.close(ctx);
        target = new ContainerTarget(given);
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "deposit into a chest";
    }

    @Override
    public int timeoutTicks() {
        return 60 * 20;
    }
}
