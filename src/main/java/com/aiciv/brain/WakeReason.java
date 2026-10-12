package com.aiciv.brain;

/** Why a bot should think. Declaration order is priority: the first entry is most urgent. */
public enum WakeReason {
    HURT,
    MENTIONED,
    ACTION_ENDED,
    IDLE
}
