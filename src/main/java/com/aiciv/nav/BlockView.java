package com.aiciv.nav;

/** What the pathfinder needs to know about blocks. Implementations must be cheap. */
public interface BlockView {
    /** No collision box: a body can be inside this block. */
    boolean passable(int x, int y, int z);

    /** The top of this block can be stood on. */
    boolean standable(int x, int y, int z);

    /** Hurts or traps anything that enters or stands on it: lava, fire, magma, cactus... */
    boolean dangerous(int x, int y, int z);
}
