package com.aiciv.dragon;

/** What to do next in the dragon fight, decided from a plain description of the situation. */
public final class DragonTactics {
    private DragonTactics() {}

    public enum Move { DODGE, SHOOT_CRYSTAL, BREAK_CAGE, HIT_HEAD, SHOOT_DRAGON, WAIT, DONE }

    /** Crystal counts, the dragon's state and what the bot carries; {@code headDistance} is to the dragon's head. */
    public record Situation(boolean inBreath, int openCrystalsVisible, int cagedCrystals, boolean dragonPerched, double headDistance,
                            boolean dragonDead, boolean hasBow, int arrows, int throwaways, float health) {}

    /** Crystals come first (they heal the dragon), and a cage is only worth breaking with room to build and some health to spare. */
    public static Move next(Situation s) {
        boolean shoots = s.hasBow() && s.arrows() > 0;
        if (s.dragonDead()) return Move.DONE;
        if (s.inBreath()) return Move.DODGE;
        if (s.openCrystalsVisible() > 0 && shoots) return Move.SHOOT_CRYSTAL;
        if (s.cagedCrystals() > 0 && shoots && s.throwaways() >= 24 && s.health() > 12) return Move.BREAK_CAGE;
        if (s.dragonPerched()) return Move.HIT_HEAD;
        if (shoots) return Move.SHOOT_DRAGON;
        return Move.WAIT;
    }
}
