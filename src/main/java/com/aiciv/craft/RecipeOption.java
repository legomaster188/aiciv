package com.aiciv.craft;

import java.util.List;
import java.util.Set;

/**
 * One crafting recipe reduced to what the planner needs.
 *
 * @param ingredients one entry per filled grid slot, each the set of acceptable item ids (no namespace)
 * @param handle      the game's {@code RecipeHolder}, opaque to the planner
 */
public record RecipeOption(String output, int outputCount, List<Set<String>> ingredients, boolean needsTable, Object handle) {}
