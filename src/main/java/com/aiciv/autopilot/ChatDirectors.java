package com.aiciv.autopilot;

import com.aiciv.autopilot.chat.PhraseBook;
import com.aiciv.autopilot.chat.TemplateChat;
import com.aiciv.autopilot.personality.Personality;
import com.aiciv.config.AiCivConfig;
import java.util.SplittableRandom;

/** Builds an autopilot's chat. */
public final class ChatDirectors {
    private ChatDirectors() {}

    public static AutopilotChat create(AutopilotSpec spec, Personality p, Tuning t, AiCivConfig.AutopilotConfig cfg) {
        return new TemplateChat(spec, p, t, cfg, PhraseBook.forPersonality(p), new SplittableRandom(spec.modelKey().hashCode() ^ p.id().hashCode()));
    }
}
