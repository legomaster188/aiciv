package com.aiciv.autopilot;

import com.aiciv.autopilot.chat.PhraseBook;
import com.aiciv.autopilot.chat.TemplateChat;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.config.AiCivConfig;
import java.util.SplittableRandom;

/** Builds an autopilot's chat. */
public final class ChatDirectors {
    private ChatDirectors() {}

    /** Seeded from the bot's name too, so two bots of one personality do not roll the same dice line for line. */
    public static AutopilotChat create(String botName, AutopilotSpec spec, Personality p, Tuning t, AiCivConfig.AutopilotConfig cfg) {
        return new TemplateChat(spec, p, t, cfg, PhraseBook.forPersonality(p), new SplittableRandom(seed(botName, spec, p)));
    }

    static long seed(String botName, AutopilotSpec spec, Personality p) {
        return 31L * botName.toLowerCase(java.util.Locale.ROOT).hashCode() + (spec.modelKey().hashCode() ^ p.id().hashCode());
    }
}
