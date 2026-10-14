package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import java.util.List;

/** Something an autopilot may pursue, as ordered steps. Pure. */
public interface Goal {
    /** One of {@link com.aiciv.autopilot.GoalIds#ALL}. */
    String id();

    /** A few words for chat and the panel, e.g. "get iron tools". */
    String summary();

    List<Step> steps();

    default boolean achieved(Situation s) {
        return steps().stream().filter(st -> !st.optional()).allMatch(st -> st.done(s));
    }

    /** How much the goal is wanted now, 0..1. */
    default double need(Situation s) {
        return achieved(s) ? 0 : 1;
    }
}
