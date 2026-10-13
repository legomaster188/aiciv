package com.aiciv.gear;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Which armor piece to put on or make next. Pure: items are plain ids, scores and affordability are passed in. */
public final class GearPlan {
    /** Slots in the order they are filled: the piece that protects most per material comes first. */
    public enum Slot {
        CHEST("chestplate"), LEGS("leggings"), HEAD("helmet"), FEET("boots");

        public final String suffix;

        Slot(String suffix) {
            this.suffix = suffix;
        }

        public static Slot of(String itemId) {
            for (Slot s : values()) if (itemId.endsWith("_" + s.suffix)) return s;
            return null;
        }
    }

    /** Materials a bot can craft, best first. Netherite needs smithing and is only ever worn if found. */
    public static final List<String> AUTO = List.of("diamond", "iron", "golden", "leather");

    public sealed interface Job {
        String item();
    }

    public record Equip(String item) implements Job {}

    public record Craft(String item) implements Job {}

    /** Armor points per slot in the order helmet, chestplate, leggings, boots. */
    private static final Map<String, int[]> POINTS = Map.of(
            "leather", new int[] {1, 3, 2, 1}, "chainmail", new int[] {2, 5, 4, 1}, "golden", new int[] {2, 5, 3, 1},
            "iron", new int[] {2, 6, 5, 2}, "diamond", new int[] {3, 8, 6, 3}, "netherite", new int[] {3, 8, 6, 3});
    private static final Map<String, Integer> TOUGHNESS = Map.of("diamond", 2, "netherite", 3);

    private GearPlan() {}

    public static String pieceId(String material, Slot slot) {
        return material + "_" + slot.suffix;
    }

    /**
     * Armor points plus toughness for the vanilla pieces, -1 for anything else. The game-side scorer reads the
     * item's attribute modifiers; a game test keeps the two equal.
     */
    public static double tableScore(String itemId) {
        Slot slot = Slot.of(itemId);
        if (slot == null) return -1;
        String material = itemId.substring(0, itemId.length() - slot.suffix.length() - 1);
        int[] points = POINTS.get(material);
        if (points == null) return -1;
        int index = switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
        };
        return points[index] + TOUGHNESS.getOrDefault(material, 0);
    }

    /** The slot each worn armor id occupies. */
    public static Map<Slot, String> wornFrom(Collection<String> ids) {
        Map<Slot, String> out = new EnumMap<>(Slot.class);
        for (String id : ids) {
            Slot slot = Slot.of(id.trim());
            if (slot != null) out.put(slot, id.trim());
        }
        return out;
    }

    /**
     * The next thing to do: put on a carried piece that beats what is worn, else make the best affordable piece
     * ({@code materials} order) that does. Null when no slot can be improved. {@code skip} holds ids that already failed.
     */
    public static Job next(Map<Slot, String> worn, Map<String, Integer> inventory, List<String> materials,
                           Predicate<String> canMake, ToDoubleFunction<String> score, Set<String> skip) {
        for (Slot slot : Slot.values()) {
            String current = worn.get(slot);
            double have = current == null ? -1 : score.applyAsDouble(current);
            String best = null;
            double bestScore = have;
            for (Map.Entry<String, Integer> e : inventory.entrySet()) {
                if (e.getValue() <= 0 || skip.contains(e.getKey()) || Slot.of(e.getKey()) != slot) continue;
                double s = score.applyAsDouble(e.getKey());
                if (s > bestScore) {
                    best = e.getKey();
                    bestScore = s;
                }
            }
            if (best != null) return new Equip(best);
            for (String material : materials) {
                String id = pieceId(material, slot);
                if (!skip.contains(id) && score.applyAsDouble(id) > have && canMake.test(id)) return new Craft(id);
            }
        }
        return null;
    }

    /**
     * Which craftable armor pieces to offer a menu model: only ones that beat what is worn, empty slots first, then the
     * best material, at most {@code cap}.
     */
    public static List<String> armorCraftTargets(Map<Slot, String> worn, Collection<String> craftable,
                                                 ToDoubleFunction<String> score, int cap) {
        List<String> useful = new ArrayList<>();
        for (String id : craftable) {
            Slot slot = Slot.of(id);
            if (slot == null) continue;
            String current = worn.get(slot);
            if (score.applyAsDouble(id) > (current == null ? -1 : score.applyAsDouble(current))) useful.add(id);
        }
        useful.sort(Comparator.<String>comparingInt(id -> worn.containsKey(Slot.of(id)) ? 1 : 0)
                .thenComparingInt(GearPlan::materialRank).thenComparingInt(id -> Slot.of(id).ordinal()));
        return useful.size() > cap ? new ArrayList<>(useful.subList(0, cap)) : useful;
    }

    /** Position of the item's material in {@link #AUTO}, best first; unknown materials last. */
    private static int materialRank(String id) {
        for (int i = 0; i < AUTO.size(); i++) if (id.startsWith(AUTO.get(i) + "_")) return i;
        return AUTO.size();
    }

    /** Sword and axe tiers for "is the carried weapon better": 0 for anything that is not one. */
    public static int weaponRank(String itemId) {
        int kind = itemId.endsWith("_sword") ? 2 : itemId.endsWith("_axe") ? 1 : 0;
        if (kind == 0) return 0;
        int tier = itemId.startsWith("netherite_") ? 5 : itemId.startsWith("diamond_") ? 4 : itemId.startsWith("iron_") ? 3
                : itemId.startsWith("stone_") ? 2 : 1;
        return tier * 10 + kind;
    }
}
