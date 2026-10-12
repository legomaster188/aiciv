package com.aiciv.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
    private static final Set<String> PLANKS = Set.of("oak_planks", "birch_planks");
    private static final Set<String> LOGS = Set.of("oak_log");

    private static RecipeOption recipe(String out, int n, boolean table, Set<String>... slots) {
        return new RecipeOption(out, n, List.of(slots), table, null);
    }

    @SuppressWarnings("unchecked")
    private static List<RecipeOption> woodRecipes() {
        return List.of(
                recipe("oak_planks", 4, false, LOGS),
                recipe("stick", 4, false, PLANKS, PLANKS),
                recipe("crafting_table", 1, false, PLANKS, PLANKS, PLANKS, PLANKS),
                recipe("wooden_pickaxe", 1, true, PLANKS, PLANKS, PLANKS, Set.of("stick"), Set.of("stick")));
    }

    private static List<String> outputs(CraftPlanner.Plan plan) {
        return plan.steps().stream().map(s -> s.recipe().output()).toList();
    }

    @Test
    void plansPickaxeFromLogsWithIntermediates() {
        CraftPlanner.Plan plan = CraftPlanner.plan("wooden_pickaxe", 1, Map.of("oak_log", 3), woodRecipes(), false);
        assertTrue(plan.missing().isEmpty(), plan.missing().toString());
        List<String> out = outputs(plan);
        assertEquals("wooden_pickaxe", out.get(out.size() - 1));
        assertTrue(out.contains("oak_planks") && out.contains("stick") && out.contains("crafting_table"), out.toString());
        assertTrue(out.indexOf("crafting_table") < out.indexOf("wooden_pickaxe"), "table must come before the table recipe: " + out);
        assertTrue(out.indexOf("stick") < out.indexOf("wooden_pickaxe"), out.toString());
        // 3 logs = 12 planks: 4 for the table, 3 for the head, 2 for the sticks.
        int logsUsed = plan.steps().stream().filter(s -> s.recipe().output().equals("oak_planks")).mapToInt(CraftPlanner.Step::times).sum();
        assertEquals(3, logsUsed);
    }

    @Test
    void usesHeldIntermediatesFirst() {
        CraftPlanner.Plan plan = CraftPlanner.plan("wooden_pickaxe", 1,
                Map.of("oak_planks", 3, "stick", 2, "crafting_table", 1), woodRecipes(), true);
        assertTrue(plan.missing().isEmpty());
        assertEquals(List.of("wooden_pickaxe"), outputs(plan));
        assertEquals(Map.of("oak_planks", 3, "stick", 2), plan.steps().get(0).uses());
    }

    @Test
    void planReportsMissingRawMaterials() {
        CraftPlanner.Plan plan = CraftPlanner.plan("wooden_pickaxe", 1, Map.of("oak_log", 1), woodRecipes(), false);
        assertFalse(plan.missing().isEmpty());
        assertTrue(plan.missing().containsKey("any log"), plan.missing().toString());
        // 2 logs short: the table (4 planks) and the pickaxe (3 planks + 2 sticks) need 3 logs in all.
        assertEquals(2, plan.missing().get("any log"));
    }

    @Test
    void doesNotLoopOnCycles() {
        List<RecipeOption> loop = List.of(
                recipe("a", 1, false, Set.of("b")),
                recipe("b", 1, false, Set.of("a")));
        CraftPlanner.Plan plan = CraftPlanner.plan("a", 1, Map.of(), loop, true);
        assertFalse(plan.missing().isEmpty());
    }

    @Test
    void countMeansItemsNotCrafts() {
        CraftPlanner.Plan plan = CraftPlanner.plan("stick", 8, Map.of("oak_planks", 4), woodRecipes(), false);
        assertTrue(plan.missing().isEmpty());
        assertEquals(1, plan.steps().size());
        assertEquals(2, plan.steps().get(0).times());
        assertEquals(4, plan.steps().get(0).uses().get("oak_planks"));
    }

    @Test
    void alreadyHoldingEnoughNeedsNoSteps() {
        CraftPlanner.Plan plan = CraftPlanner.plan("stick", 2, Map.of("stick", 5), woodRecipes(), false);
        assertTrue(plan.steps().isEmpty() && plan.missing().isEmpty());
    }

    @Test
    void usesTheWoodTypeThatIsHeld() {
        CraftPlanner.Plan plan = CraftPlanner.plan("stick", 4, Map.of("birch_planks", 2), woodRecipes(), false);
        assertTrue(plan.missing().isEmpty());
        assertEquals(Map.of("birch_planks", 2), plan.steps().get(0).uses());
    }

    @Test
    void missingNamesTheLeafWhenNoFamilyApplies() {
        List<RecipeOption> recipes = List.of(recipe("stone_pickaxe", 1, false, Set.of("cobblestone"), Set.of("stick")));
        CraftPlanner.Plan plan = CraftPlanner.plan("stone_pickaxe", 1, Map.of("stick", 1), recipes, true);
        assertEquals(Map.of("cobblestone", 1), plan.missing());
    }

    @Test
    void canMakeCountsExtraItemsAndTable() {
        List<RecipeOption> recipes = woodRecipes();
        assertTrue(CraftPlanner.canMake("wooden_pickaxe", 1, Map.of("oak_log", 3), recipes, false));
        assertFalse(CraftPlanner.canMake("wooden_pickaxe", 1, Map.of("oak_log", 2), recipes, false));
        assertTrue(CraftPlanner.canMake("wooden_pickaxe", 1, Map.of("oak_log", 2), recipes, true));
        assertFalse(CraftPlanner.canMake("wooden_pickaxe", 1, Map.of("oak_log", 1), recipes, true));
    }
}
