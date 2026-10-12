package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.NonNullList;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

/** Inventory queries and moves a survival player could do by hand. */
public final class InventoryOps {
    private InventoryOps() {}

    public static int count(AiPlayer p, Predicate<ItemStack> match) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) if (!s.isEmpty() && match.test(s)) n += s.getCount();
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty() && match.test(off)) n += off.getCount();
        return n;
    }

    public static boolean has(AiPlayer p, Item item, int n) {
        return count(p, s -> s.is(item)) >= n;
    }

    public static int remove(AiPlayer p, Predicate<ItemStack> match, int n) {
        int removed = 0;
        NonNullList<ItemStack> main = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < main.size() && removed < n; i++) {
            ItemStack s = main.get(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int take = Math.min(s.getCount(), n - removed);
            s.shrink(take);
            removed += take;
        }
        ItemStack off = p.getOffhandItem();
        if (removed < n && !off.isEmpty() && match.test(off)) {
            int take = Math.min(off.getCount(), n - removed);
            off.shrink(take);
            removed += take;
        }
        return removed;
    }

    /** Items that do not fit are dropped at the bot's feet, never deleted. */
    public static void addOrDrop(AiPlayer p, ItemStack stack) {
        p.getInventory().add(stack);
        if (!stack.isEmpty()) p.drop(stack, false, Prediction.SERVER_ONLY);
    }

    public static boolean selectBest(AiPlayer p, ToDoubleFunction<ItemStack> score) {
        NonNullList<ItemStack> main = p.getInventory().getNonEquipmentItems();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (s.isEmpty()) continue;
            double v = score.applyAsDouble(s);
            if (v > bestScore) {
                bestScore = v;
                best = i;
            }
        }
        if (best < 0) return false;
        Inventory inv = p.getInventory();
        if (best >= Inventory.getSelectionSize()) {
            int sel = inv.getSelectedSlot();
            ItemStack moved = main.get(best);
            inv.setItem(best, main.get(sel));
            inv.setItem(sel, moved);
            best = sel;
        }
        inv.setSelectedSlot(best);
        return true;
    }

    public static ItemStack bestToolFor(AiPlayer p, BlockState s) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0F; // bare hand
        for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            if (s.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(s)) continue;
            float speed = stack.getDestroySpeed(s);
            if (speed > bestSpeed) {
                best = stack;
                bestSpeed = speed;
            }
        }
        return best;
    }

    public static boolean isThrowaway(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(Items.DIRT) || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.NETHERRACK)) return true;
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_planks");
    }

    public static int throwawayCount(AiPlayer p) {
        return count(p, InventoryOps::isThrowaway);
    }

    public static boolean selectThrowaway(AiPlayer p) {
        return selectBest(p, s -> isThrowaway(s) ? 1 : 0);
    }
}
