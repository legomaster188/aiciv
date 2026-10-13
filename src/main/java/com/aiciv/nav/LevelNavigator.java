package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.InventoryOps;
import net.minecraft.core.BlockPos;

/** Plans with a time-sliced A* search, then walks the path; replans when stuck. */
public final class LevelNavigator implements Navigator {
    static final int MAX_REPLANS = 3;

    private final AiPlayer player;
    private final NavBudget budget;
    private final BlockBreaker breaker;
    private Goal goal;
    private PathSearch search;
    private PathFollower follower;
    private int replans;
    private NavStatus status = NavStatus.IDLE;

    public LevelNavigator(AiPlayer player, NavBudget budget, BlockBreaker breaker) {
        this.player = player;
        this.budget = budget;
        this.breaker = breaker;
    }

    @Override
    public void goTo(Goal goal) {
        this.goal = goal;
        replans = 0;
        plan();
    }

    @Override
    public NavStatus status() {
        return status;
    }

    @Override
    public void stop() {
        cancelSearch();
        goal = null;
        follower = null;
        breaker.cancel();
        status = NavStatus.IDLE;
        player.stopMoving();
    }

    /** Call once per server tick, before entities tick. */
    public void tick() {
        if (goal == null) return;
        if (goal.reached(feet()) && (player.onGround() || player.isInWater())) {
            if (follower != null && follower.finishingDoor(player)) return;
            arrive();
            return;
        }
        if (search != null) {
            switch (search.state()) {
                case RUNNING -> {
                    return;
                }
                case FAILED -> {
                    fail(search.failure());
                    return;
                }
                case FOUND -> {
                    follower = new PathFollower(search.steps(), breaker);
                    search = null;
                }
            }
        }
        if (follower == null) return;
        switch (follower.tick(player)) {
            case MOVING -> { }
            case ARRIVED -> {
                // A swimmer floats below the water cell the planner chose, so the last step counts too.
                if (goal.reached(feet()) || goal.reached(follower.last())) arrive();
                else replanOrFail();
            }
            case STUCK -> replanOrFail();
        }
    }

    private void plan() {
        cancelSearch();
        follower = null;
        breaker.cancel();
        player.stopMoving();
        SearchOptions options = new SearchOptions(true, InventoryOps.throwawayCount(player) > 0);
        search = new PathSearch(new LevelBlockView(player.level(), player), feet(), goal, PathSearch.MAX_NODES, options);
        budget.submit(search);
        status = NavStatus.RUNNING;
    }

    private void replanOrFail() {
        if (++replans > MAX_REPLANS) {
            BlockPos b = player.blockPosition();
            fail("stuck at " + b.getX() + " " + b.getY() + " " + b.getZ());
        } else {
            plan();
        }
    }

    private void arrive() {
        cancelSearch();
        follower = null;
        breaker.cancel();
        goal = null;
        status = NavStatus.ARRIVED;
        player.stopMoving();
    }

    private void fail(String reason) {
        cancelSearch();
        follower = null;
        breaker.cancel();
        goal = null;
        status = NavStatus.failed(reason);
        player.stopMoving();
    }

    private void cancelSearch() {
        if (search != null) {
            search.cancel();
            search = null;
        }
    }

    private Pos feet() {
        BlockPos b = player.blockPosition();
        return new Pos(b.getX(), b.getY(), b.getZ());
    }
}
