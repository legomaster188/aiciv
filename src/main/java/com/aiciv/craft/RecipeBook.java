package com.aiciv.craft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** The game's shaped and shapeless recipes as {@link RecipeOption}s. Special recipes (dyeing, fireworks, ...) are left out. */
public final class RecipeBook {
    private static RecipeManager cachedFor;
    private static List<RecipeOption> cached = List.of();

    private RecipeBook() {}

    /** Cached until the server's recipe manager is replaced (a data pack reload builds a new one). */
    public static synchronized List<RecipeOption> load(ServerLevel level) {
        RecipeManager manager = level.recipeAccess();
        if (manager != cachedFor) {
            cached = build(manager);
            cachedFor = manager;
        }
        return cached;
    }

    private static List<RecipeOption> build(RecipeManager manager) {
        List<RecipeOption> out = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) continue;
            if (recipe.placementInfo().isImpossibleToPlace()) continue;
            RecipeOption option = convert(holder);
            if (option != null) out.add(option);
        }
        return out;
    }

    private static RecipeOption convert(RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        List<Ingredient> filled = new ArrayList<>();
        boolean needsTable;
        List<ItemStack> grid = new ArrayList<>();
        CraftingInput input;
        if (recipe instanceof ShapedRecipe shaped) {
            needsTable = shaped.getWidth() > 2 || shaped.getHeight() > 2;
            for (Optional<Ingredient> slot : shaped.getIngredients()) {
                if (slot.isEmpty()) {
                    grid.add(ItemStack.EMPTY);
                    continue;
                }
                filled.add(slot.get());
                grid.add(firstStack(slot.get()));
            }
            input = CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid);
        } else {
            filled.addAll(recipe.placementInfo().ingredients());
            needsTable = filled.size() > 4;
            for (Ingredient ingredient : filled) grid.add(firstStack(ingredient));
            while (grid.size() < 9) grid.add(ItemStack.EMPTY);
            input = CraftingInput.of(3, 3, grid);
        }
        List<Set<String>> slots = new ArrayList<>();
        for (Ingredient ingredient : filled) {
            Set<String> ids = new LinkedHashSet<>();
            ingredient.items().forEach(h -> ids.add(idOf(h.value())));
            if (ids.isEmpty()) return null;
            slots.add(ids);
        }
        if (slots.isEmpty()) return null;
        ItemStack result;
        try {
            result = ((CraftingRecipe) recipe).assemble(input);
        } catch (RuntimeException e) {
            return null; // a recipe that cannot assemble from an arbitrary first ingredient is not one we can plan with
        }
        if (result.isEmpty()) return null;
        return new RecipeOption(idOf(result.getItem()), result.getCount(), List.copyOf(slots), needsTable, holder);
    }

    private static ItemStack firstStack(Ingredient ingredient) {
        Optional<Holder<Item>> first = ingredient.items().findFirst();
        return first.map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
