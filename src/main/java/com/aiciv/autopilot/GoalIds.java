package com.aiciv.autopilot;

import java.util.List;

/** Every goal id, in library order (Task 2's core goals' ids are first, Task 5's world goals after). */
public final class GoalIds {
    public static final List<String> ALL = List.of("get_wood_tools", "get_stone_tools", "get_iron_tools", "get_iron_armor",
            "get_food", "defend", "social", "build_base", "store_items", "farm", "trade_emeralds", "mine_diamonds", "explore",
            "beat_game");

    private GoalIds() {}
}
