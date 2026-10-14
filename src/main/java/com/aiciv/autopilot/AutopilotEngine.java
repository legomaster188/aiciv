package com.aiciv.autopilot;

import com.aiciv.autopilot.personality.Personality;

/** Decides what an autopilot does next. Pure (no world access) and fast: well under half a millisecond per call. */
public interface AutopilotEngine {
    Decision decide(Situation s, AutopilotMemory mem, Personality p, String trigger, long tick);

    default Tuning tuning(Personality p) {
        return Tuning.DEFAULT;
    }
}
