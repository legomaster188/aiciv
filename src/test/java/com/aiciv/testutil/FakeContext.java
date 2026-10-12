package com.aiciv.testutil;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Navigator;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.KnownBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class FakeContext implements BotContext {
    public Vec3 position = new Vec3(0.5, 64, 0.5);
    public final Map<String, Vec3> players = new HashMap<>();
    public final List<String> said = new ArrayList<>();
    public final FakeNavigator navigator = new FakeNavigator();

    @Override public Vec3 position() { return position; }
    @Override public Optional<Vec3> playerPosition(String name) { return Optional.ofNullable(players.get(name)); }
    @Override public Navigator navigator() { return navigator; }
    @Override public void say(String text) { said.add(text); }
    @Override public AiPlayer player() { return null; }
    @Override public KnownBlocks knownBlocks() { return null; }
    @Override public BlockBreaker breaker() { return null; }
}
