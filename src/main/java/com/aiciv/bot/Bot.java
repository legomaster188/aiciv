package com.aiciv.bot;

import com.aiciv.actions.ActionExecutor;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.autopilot.AutopilotBrain;
import com.aiciv.brain.BotBrain;
import com.aiciv.nav.LevelNavigator;
import com.aiciv.reflex.ReflexController;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.KnownBlocks;

/** One online AI player and everything that drives it. */
public final class Bot {
    public final String name;
    /** Replaced by a new instance when the bot respawns; swapped together with navigator, breaker and context. */
    public AiPlayer player;
    public final BotBrain brain;
    public final ActionExecutor executor;
    public LevelNavigator navigator;
    public BlockBreaker breaker;
    public final KnownBlocks knownBlocks = new KnownBlocks(128);
    /** Module memory (chests, villagers...); survives respawn like {@link #knownBlocks}. */
    public final Attachments attachments = new Attachments();
    public final ReflexController reflexes = new ReflexController();
    public BotContext context;
    /** The planner state when the bot's model is an autopilot; null for LLM bots. */
    public AutopilotBrain autopilot;
    /** Full id of the dimension the bot was in at the last tick, e.g. {@code minecraft:the_nether}. */
    public String dimension;
    /** Counts deaths, so a reply asked for in an earlier life is dropped. */
    int life;
        /** Set between a death and the respawn on the next tick. */
    Death death;

    record Death(String message, int x, int y, int z) {}

    /** True between a death and the respawn on the next tick. */
    public boolean dead() {
        return death != null || player.isDeadOrDying();
    }

    Bot(String name, AiPlayer player, BotBrain brain, ActionExecutor executor, LevelNavigator navigator,
        BlockBreaker breaker) {
        this.name = name;
        this.player = player;
        this.brain = brain;
        this.executor = executor;
        this.navigator = navigator;
        this.breaker = breaker;
    }
}
