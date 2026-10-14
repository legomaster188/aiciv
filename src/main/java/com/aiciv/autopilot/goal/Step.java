package com.aiciv.autopilot.goal;

import com.aiciv.autopilot.Situation;
import java.util.Set;

/** One step of a goal: done when its check passes, otherwise {@link #next} says what to do about it. Pure. */
public interface Step {
    String id();

    boolean done(Situation s);

    /** Action names as the catalog spells them; a missing one skips an optional step and blocks any other. */
    default Set<String> needs() {
        return Set.of();
    }

    default boolean optional() {
        return false;
    }

    /** Set in memory when this step's actions finish; null for none. */
    default String flag() {
        return null;
    }

    Move next(Situation s);
}
