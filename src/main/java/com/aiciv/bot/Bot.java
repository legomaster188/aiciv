package com.aiciv.bot;

import com.aiciv.actions.ActionExecutor;
import com.aiciv.actions.BotContext;
import com.aiciv.brain.BotBrain;
import com.aiciv.nav.LevelNavigator;

/** One online AI player and everything that drives it. */
public final class Bot {
    public final String name;
    public final AiPlayer player;
    public final BotBrain brain;
    public final ActionExecutor executor;
    public final LevelNavigator navigator;
    public BotContext context;

    Bot(String name, AiPlayer player, BotBrain brain, ActionExecutor executor, LevelNavigator navigator) {
        this.name = name;
        this.player = player;
        this.brain = brain;
        this.executor = executor;
        this.navigator = navigator;
    }
}
