package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Calls;
import com.aiciv.autopilot.Situation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Step factories shared by the goals. Pure. */
public final class Steps {
    private Steps() {}

    private static final List<String> ARMOR_PIECES = List.of("helmet", "chestplate", "leggings", "boots");

    public static final Step WOODEN_PICKAXE = craft("wooden_pickaxe", 1);
    public static final Step STONE_PICKAXE = craft("stone_pickaxe", 1);
    public static final Step IRON_PICKAXE = craft("iron_pickaxe", 1);

    /** Gather what the recipe is short of, then craft it. Done when the tool (or better), the worn armor or enough of the item is held. */
    public static Step craft(String item, int count) {
        return new Step() {
            @Override public String id() { return item; }
            @Override public boolean done(Situation s) { return craftDone(item, count, s); }
            @Override public Set<String> needs() { return Set.of("craft", "mine"); }
            @Override public Move next(Situation s) {
                Map<String, Integer> missing = Costs.missing(Costs.forCraft(item, count, s), s);
                if (!missing.isEmpty()) {
                    Map.Entry<String, Integer> first = missing.entrySet().iterator().next();
                    return Gather.move(first.getKey(), first.getValue(), s);
                }
                return Move.act("craft " + count + " " + item, Calls.of("craft", "item", item, "count", count));
            }
        };
    }

    private static boolean craftDone(String item, int count, Situation s) {
        if (isTool(item)) return s.hasTool(item);
        if (ARMOR_PIECES.stream().anyMatch(p -> item.endsWith("_" + p))) return s.worn().contains(item) || s.count(item) >= count;
        return s.count(item) >= count;
    }

    private static boolean isTool(String item) {
        return item.endsWith("_pickaxe") || item.endsWith("_axe") || item.endsWith("_sword") || item.endsWith("_shovel") || item.endsWith("_hoe");
    }

    /** Any other step. */
    public static Step of(String id, Predicate<Situation> done, Function<Situation, Move> next, Set<String> needs,
                          boolean optional, String flag) {
        return new Step() {
            @Override public String id() { return id; }
            @Override public boolean done(Situation s) { return done.test(s); }
            @Override public Set<String> needs() { return needs; }
            @Override public boolean optional() { return optional; }
            @Override public String flag() { return flag; }
            @Override public Move next(Situation s) { return next.apply(s); }
        };
    }
}
