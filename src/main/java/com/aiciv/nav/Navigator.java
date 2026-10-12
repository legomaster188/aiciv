package com.aiciv.nav;

/** Moves one bot toward a goal over many ticks. */
public interface Navigator {
    /** Starts (or restarts) navigation toward the goal. */
    void goTo(Goal goal);

    NavStatus status();

    /** Stops moving and forgets the goal. */
    void stop();
}
