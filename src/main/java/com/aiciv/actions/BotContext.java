package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Navigator;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.KnownBlocks;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/** What an action may see and do. Implemented by the live world and by test fakes. */
public interface BotContext {
    Vec3 position();

    /** Position of an online, non-spectating player in the bot's dimension. */
    Optional<Vec3> playerPosition(String name);

    Navigator navigator();

    /** Posts a chat line as the bot. */
    void say(String text);

    /** The bot's own player. Null in unit-test fakes, so only game-test-backed actions may use it. */
    AiPlayer player();

    KnownBlocks knownBlocks();

    /**
     * A breaker for actions that dig on purpose. It is not the navigator's breaker, which the navigator
     * cancels whenever it plans, stops or arrives, so an action that breaks while navigating would lose its dig.
     */
    BlockBreaker breaker();
}
