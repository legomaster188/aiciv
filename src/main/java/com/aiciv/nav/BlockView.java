package com.aiciv.nav;

/** What the pathfinder needs to know about blocks. Implementations must be cheap. */
public interface BlockView {
    /** No collision box: a body can be inside this block. */
    boolean passable(int x, int y, int z);

    /** The top of this block can be stood on. */
    boolean standable(int x, int y, int z);

    /** Hurts or traps anything that enters or stands on it: lava, fire, magma, cactus... */
    boolean dangerous(int x, int y, int z);

    /**
     * Seconds to break this block with the best tool at hand; +infinity = unbreakable or must not be broken
     * (fluids, dangerous blocks, anything with a block entity such as chests, furnaces and beds).
     */
    double breakSeconds(int x, int y, int z);

    /** Water source or flowing water with no collision. */
    boolean water(int x, int y, int z);

    /** A closed door or fence gate the walker can open by hand. */
    default boolean openable(int x, int y, int z) { return false; }
}
