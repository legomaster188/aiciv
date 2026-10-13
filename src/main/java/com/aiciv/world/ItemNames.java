package com.aiciv.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Turns what a model calls an item into the item, a test over stacks, or "did you mean" suggestions. */
public final class ItemNames {
    private ItemNames() {}

    /**
     * The registry item named exactly by {@code query} (lower case, no namespace, spaces as underscores), else by its
     * {@link BlockNames#normalise} form ("Oak Planks", "torches"); null for air or no such item.
     */
    public static Item item(String query) {
        if (query == null) return null;
        String raw = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (raw.startsWith("minecraft:")) raw = raw.substring("minecraft:".length());
        for (String id : new String[] {raw, BlockNames.normalise(query)}) {
            if (id.isEmpty() || id.equals("air")) continue;
            Identifier key = Identifier.tryParse("minecraft:" + id);
            if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
                Item item = BuiltInRegistries.ITEM.getValue(key);
                if (item != Items.AIR) return item;
            }
        }
        return null;
    }

    /** Stacks of the exact item when the query names one, else stacks whose id {@link BlockNames#matcher} accepts. */
    public static Predicate<ItemStack> matcher(String query) {
        Item exact = item(query);
        if (exact != null) return s -> !s.isEmpty() && s.is(exact);
        Predicate<String> ids = BlockNames.matcher(query);
        return s -> !s.isEmpty() && ids.test(id(s));
    }

    public static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** The {@code n} item ids closest to {@code query} by edit distance, for "no item called x (did you mean ...)". */
    public static List<String> suggestions(String query, int n) {
        List<String> ids = new ArrayList<>();
        for (Identifier key : BuiltInRegistries.ITEM.keySet()) if (!key.getPath().equals("air")) ids.add(key.getPath());
        return rank(BlockNames.normalise(query), ids, n);
    }

    /** Pure: the {@code n} ids with the smallest edit distance to {@code query}; ties alphabetical. */
    public static List<String> rank(String query, List<String> ids, int n) {
        record Scored(String id, int distance) {}
        List<Scored> scored = new ArrayList<>();
        for (String id : ids) scored.add(new Scored(id, levenshtein(query, id)));
        scored.sort(Comparator.comparingInt(Scored::distance).thenComparing(Scored::id));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scored.size()); i++) out.add(scored.get(i).id());
        return out;
    }

    /** Insertions, deletions and substitutions needed to turn {@code a} into {@code b}. */
    public static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                row[j] = Math.min(substitute, Math.min(previous[j] + 1, row[j - 1] + 1));
            }
            int[] t = previous;
            previous = row;
            row = t;
        }
        return previous[b.length()];
    }
}
