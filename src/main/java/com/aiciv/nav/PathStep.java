package com.aiciv.nav;

import java.util.List;

/**
 * One step of a path. {@code breaks} are blocks to break before entering {@code to}; {@code place} is the
 * block to place (BRIDGE: the floor under {@code to}, PILLAR: the block the bot stood in) or null;
 * {@code open} is a closed door or fence gate (its feet cell) to open before entering, or null.
 */
public record PathStep(Pos to, Move move, List<Pos> breaks, Pos place, Pos open) {
    public PathStep(Pos to, Move move, List<Pos> breaks, Pos place) {
        this(to, move, breaks, place, null);
    }
}
