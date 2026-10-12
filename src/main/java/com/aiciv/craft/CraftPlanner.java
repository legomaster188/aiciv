package com.aiciv.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which recipes to craft, in order, to end up holding a number of an item.
 * Pure: it only reads inventory counts and the recipe list, so it can be tested without a game.
 */
public final class CraftPlanner {
    public static final String TABLE = "crafting_table";
    static final int MAX_DEPTH = 4;

    /** {@code uses}: how many of each item the step consumes in total over all its {@code times}. */
    public record Step(RecipeOption recipe, int times, Map<String, Integer> uses) {}

    /** {@code missing} is empty when the plan can be carried out; otherwise it names what the bot lacks. */
    public record Plan(List<Step> steps, Map<String, Integer> missing) {}

    private CraftPlanner() {}

    /** Can this inventory make {@code count} more of {@code item} now, crafting intermediates and placing a table if needed? */
    public static boolean canMake(String item, int count, Map<String, Integer> inventory, List<RecipeOption> recipes, boolean tableNearby) {
        return plan(item, inventory.getOrDefault(item, 0) + count, inventory, recipes, tableNearby).missing().isEmpty();
    }

    /** Plans for the inventory to hold {@code count} of {@code target} (crafting only the shortfall). */
    public static Plan plan(String target, int count, Map<String, Integer> inventory, List<RecipeOption> recipes, boolean tableNearby) {
        Search search = new Search(recipes);
        State strict = new State(inventory);
        boolean needTable = false;
        if (search.obtain(target, count, strict, new HashSet<>(), 0, false)) {
            if (tableNearby || !needsTable(strict.steps)) return new Plan(strict.steps, Map.of());
            needTable = true;
        } else if (!tableNearby) {
            State probe = new State(inventory);
            search.obtain(target, count, probe, new HashSet<>(), 0, true);
            needTable = needsTable(probe.steps);
        }
        if (needTable) {
            State withTable = new State(inventory);
            if (search.obtainTable(withTable, false) && search.obtain(target, count, withTable, new HashSet<>(), 0, false)) {
                return new Plan(withTable.steps, Map.of());
            }
        }
        State report = new State(inventory);
        if (needTable) search.obtainTable(report, true);
        search.obtain(target, count, report, new HashSet<>(), 0, true);
        if (report.missing.isEmpty()) report.missing.put(displayName(target), count); // greedy choices disagreed; never claim success
        return new Plan(report.steps, report.missing);
    }

    private static boolean needsTable(List<Step> steps) {
        for (Step s : steps) if (s.recipe().needsTable()) return true;
        return false;
    }

    /** What a model should be told to gather: log and planks families collapse to "any log" / "any planks". */
    static String displayName(String item) {
        if (item.endsWith("_log") || item.endsWith("_stem")) return "any log";
        if (item.endsWith("_planks")) return "any planks";
        return item;
    }

    private static final class State {
        final Map<String, Integer> sim;
        final List<Step> steps;
        final Map<String, Integer> missing;

        State(Map<String, Integer> inventory) {
            sim = new HashMap<>(inventory);
            steps = new ArrayList<>();
            missing = new LinkedHashMap<>();
        }

        private State(State other) {
            sim = new HashMap<>(other.sim);
            steps = new ArrayList<>(other.steps);
            missing = new LinkedHashMap<>(other.missing);
        }

        State copy() {
            return new State(this);
        }

        void adopt(State other) {
            sim.clear();
            sim.putAll(other.sim);
            steps.clear();
            steps.addAll(other.steps);
            missing.clear();
            missing.putAll(other.missing);
        }

        int held(String item) {
            return sim.getOrDefault(item, 0);
        }

        int missingTotal() {
            int n = 0;
            for (int v : missing.values()) n += v;
            return n;
        }
    }

    private static final class Search {
        private final Map<String, List<RecipeOption>> byOutput = new HashMap<>();

        Search(List<RecipeOption> recipes) {
            for (RecipeOption r : recipes) byOutput.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
        }

        /** The table is placed, not consumed by recipes, so it leaves the simulated inventory once held. */
        boolean obtainTable(State st, boolean lenient) {
            if (!obtain(TABLE, 1, st, new HashSet<>(), 0, lenient)) return false;
            st.sim.merge(TABLE, -1, Integer::sum);
            return true;
        }

        /**
         * Makes {@code st} hold at least {@code n} of {@code item}, crafting what is short. Strict mode
         * returns false (leaving {@code st} unspecified) when impossible; lenient mode records what is
         * missing, pretends it was found, and always returns true.
         */
        boolean obtain(String item, int n, State st, Set<String> path, int depth, boolean lenient) {
            int have = st.held(item);
            if (have >= n) return true;
            int shortfall = n - have;
            List<RecipeOption> options = byOutput.getOrDefault(item, List.of());
            if (path.contains(item) || depth >= MAX_DEPTH || options.isEmpty()) {
                if (!lenient) return false;
                st.missing.merge(displayName(item), shortfall, Integer::sum);
                st.sim.put(item, n);
                return true;
            }
            path.add(item);
            try {
                List<RecipeOption> ordered = new ArrayList<>(options);
                // Recipes whose ingredients are all already held come first.
                ordered.sort((a, b) -> Boolean.compare(allHeld(b, st), allHeld(a, st)));
                for (RecipeOption r : ordered) {
                    State trial = st.copy();
                    if (apply(r, shortfall, trial, path, depth, false)) {
                        st.adopt(trial);
                        return true;
                    }
                }
                if (!lenient) return false;
                State best = null;
                for (RecipeOption r : ordered) {
                    State trial = st.copy();
                    apply(r, shortfall, trial, path, depth, true);
                    if (best == null || trial.missingTotal() < best.missingTotal()) best = trial;
                }
                st.adopt(best);
                return true;
            } finally {
                path.remove(item);
            }
        }

        private static boolean allHeld(RecipeOption r, State st) {
            for (Set<String> slot : r.ingredients()) {
                boolean any = false;
                for (String id : slot) if (st.held(id) > 0) any = true;
                if (!any) return false;
            }
            return true;
        }

        /** Crafts {@code recipe} enough times to gain {@code shortfall} items, obtaining its ingredients first. */
        private boolean apply(RecipeOption recipe, int shortfall, State st, Set<String> path, int depth, boolean lenient) {
            int times = (shortfall + recipe.outputCount() - 1) / recipe.outputCount();
            Map<String, Integer> uses = new LinkedHashMap<>();
            for (Set<String> slot : recipe.ingredients()) {
                String chosen = obtainFrom(slot, times, st, path, depth + 1, lenient);
                if (chosen == null) return false;
                st.sim.merge(chosen, -times, Integer::sum);
                uses.merge(chosen, times, Integer::sum);
            }
            st.sim.merge(recipe.output(), times * recipe.outputCount(), Integer::sum);
            st.steps.add(new Step(recipe, times, uses));
            return true;
        }

        /** Obtains {@code n} of one of {@code candidates}, preferring what is held most. Returns the item chosen. */
        private String obtainFrom(Set<String> candidates, int n, State st, Set<String> path, int depth, boolean lenient) {
            List<String> ordered = new ArrayList<>(candidates);
            // Held first; among equals, ones that can be crafted beat raw items, so a shortage is reported as the raw material.
            ordered.sort(Comparator.<String>comparingInt(c -> -st.held(c)).thenComparing(c -> byOutput.containsKey(c) ? 0 : 1));
            for (String c : ordered) {
                State trial = st.copy();
                if (obtain(c, n, trial, path, depth, false)) {
                    st.adopt(trial);
                    return c;
                }
            }
            if (!lenient) return null;
            String bestItem = null;
            State best = null;
            for (String c : ordered) {
                State trial = st.copy();
                obtain(c, n, trial, path, depth, true);
                if (best == null || trial.missingTotal() < best.missingTotal()) {
                    best = trial;
                    bestItem = c;
                }
            }
            st.adopt(best);
            return bestItem;
        }
    }
}
