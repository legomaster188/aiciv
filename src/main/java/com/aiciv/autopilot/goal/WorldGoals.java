package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.goal.world.BuildBase;
import com.aiciv.autopilot.goal.world.Defend;
import com.aiciv.autopilot.goal.world.Explore;
import com.aiciv.autopilot.goal.world.Farm;
import com.aiciv.autopilot.goal.world.Social;
import com.aiciv.autopilot.goal.world.StoreItems;
import com.aiciv.autopilot.goal.world.TradeEmeralds;
import java.util.List;

/** The world goals (defend, social, home, storage, farm, trade, explore), in library order after the core goals. */
public final class WorldGoals {
    private WorldGoals() {}

    private static final List<Goal> GOALS = List.of(new Defend(), new Social(), new BuildBase(), new StoreItems(), new Farm(),
            new TradeEmeralds(), new Explore());

    public static List<Goal> list() {
        return GOALS;
    }
}
