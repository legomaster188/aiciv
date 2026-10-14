package com.aiciv.autopilot;

import com.aiciv.autopilot.personality.Personality;
import com.aiciv.config.AiCivConfig;

/** Builds an autopilot's chat. */
public final class ChatDirectors {
    private ChatDirectors() {}

    public static AutopilotChat create(AutopilotSpec spec, Personality p, Tuning t, AiCivConfig.AutopilotConfig cfg) {
        return AutopilotChat.NONE;
    }
}
