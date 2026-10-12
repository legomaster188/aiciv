package com.aiciv.bot;

import com.aiciv.actions.BotContext;
import com.aiciv.nav.Navigator;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class LiveContext implements BotContext {
    private final BotManager manager;
    private final Bot bot;

    LiveContext(BotManager manager, Bot bot) {
        this.manager = manager;
        this.bot = bot;
    }

    @Override
    public Vec3 position() {
        return bot.player.position();
    }

    @Override
    public Optional<Vec3> playerPosition(String name) {
        ServerPlayer p = bot.player.level().getServer().getPlayerList().getPlayerByName(name);
        if (p == null || p.level() != bot.player.level() || p.isSpectator()) return Optional.empty();
        return Optional.of(p.position());
    }

    @Override
    public Navigator navigator() {
        return bot.navigator;
    }

    @Override
    public void say(String text) {
        manager.botSays(bot, text);
    }
}
