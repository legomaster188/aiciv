package com.aiciv.actions;

import com.aiciv.nav.Navigator;
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
}
