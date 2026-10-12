package com.aiciv.nav;

public interface Goal {
    boolean reached(Pos feet);

    /** Estimated remaining path cost from feet. Close to a lower bound, so A* paths stay near-optimal. */
    double estimate(Pos feet);

    String describe();
}
