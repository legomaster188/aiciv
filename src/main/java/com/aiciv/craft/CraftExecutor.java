package com.aiciv.craft;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

/** Carries out planned steps against the bot's real inventory, the way a player filling a crafting grid would. */
public final class CraftExecutor {
    private CraftExecutor() {}

    /** Item id (no namespace) to how many the bot holds, hotbar, main inventory and offhand. */
    public static Map<String, Integer> inventoryCounts(AiPlayer p) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) counts.merge(RecipeBook.idOf(s.getItem()), s.getCount(), Integer::sum);
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) counts.merge(RecipeBook.idOf(off.getItem()), off.getCount(), Integer::sum);
        return counts;
    }

    /** Crafts {@code step.times()} times. False when the inventory does not hold what a craft needs or the game rejects the grid. */
    public static boolean apply(AiPlayer p, CraftPlanner.Step step) {
        ServerLevel level = p.level();
        RecipeHolder<?> holder = (RecipeHolder<?>) step.recipe().handle();
        CraftingRecipe recipe = (CraftingRecipe) holder.value();
        Map<String, Integer> budget = new HashMap<>(step.uses());
        for (int i = 0; i < step.times(); i++) {
            Map<String, Integer> held = inventoryCounts(p);
            List<Item> chosen = new ArrayList<>();
            for (Set<String> slot : step.recipe().ingredients()) {
                String id = choose(slot, budget, held);
                if (id == null) return false;
                held.merge(id, -1, Integer::sum);
                budget.merge(id, -1, Integer::sum);
                chosen.add(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id)));
            }
            CraftingInput input = buildInput(recipe, chosen);
            if (!recipe.matches(input, level)) return false;
            for (Item item : chosen) InventoryOps.remove(p, s -> s.is(item), 1);
            ItemStack result = recipe.assemble(input);
            for (ItemStack left : recipe.getRemainingItems(input)) {
                if (!left.isEmpty()) InventoryOps.addOrDrop(p, left.copy());
            }
            InventoryOps.addOrDrop(p, result);
        }
        return true;
    }

    /** Prefers the item the plan budgeted for this step; otherwise whichever acceptable item is held most. */
    private static String choose(Set<String> slot, Map<String, Integer> budget, Map<String, Integer> held) {
        String best = null;
        for (String id : slot) {
            if (held.getOrDefault(id, 0) <= 0 || budget.getOrDefault(id, 0) <= 0) continue;
            if (best == null || budget.get(id) > budget.get(best)) best = id;
        }
        if (best != null) return best;
        for (String id : slot) {
            if (held.getOrDefault(id, 0) > 0 && (best == null || held.get(id) > held.get(best))) best = id;
        }
        return best;
    }

    /** The grid for the recipe: shaped recipes keep their pattern with empty cells, shapeless ones are padded to 3x3. */
    private static CraftingInput buildInput(CraftingRecipe recipe, List<Item> chosen) {
        List<ItemStack> grid = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            int next = 0;
            for (Optional<?> slot : shaped.getIngredients()) {
                grid.add(slot.isEmpty() ? ItemStack.EMPTY : new ItemStack(chosen.get(next++)));
            }
            return CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid);
        }
        for (Item item : chosen) grid.add(new ItemStack(item));
        while (grid.size() < 9) grid.add(ItemStack.EMPTY);
        return CraftingInput.of(3, 3, grid);
    }
}
