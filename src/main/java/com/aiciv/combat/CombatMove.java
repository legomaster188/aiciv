package com.aiciv.combat;

/** One exact step of a {@code combat} chain. A move that names a target always carries it (the parser fills carry-overs). */
public sealed interface CombatMove {
    /** The most game ticks the move can take; the action's timeout adds them up. */
    int maxTicks();

    record AttackOnce(String target) implements CombatMove {
        @Override
        public int maxTicks() {
            return 60;
        }
    }

    record Strafe(boolean left, int ticks) implements CombatMove {
        @Override
        public int maxTicks() {
            return ticks;
        }
    }

    record BackOff(int ticks) implements CombatMove {
        @Override
        public int maxTicks() {
            return ticks;
        }
    }

    record JumpCrit(String target) implements CombatMove {
        @Override
        public int maxTicks() {
            return 30;
        }
    }

    record Shield(int ticks) implements CombatMove {
        @Override
        public int maxTicks() {
            return ticks;
        }
    }

    record Bow(String target, int chargeTicks) implements CombatMove {
        @Override
        public int maxTicks() {
            return chargeTicks + 20;
        }
    }

    record Hotbar(int slot) implements CombatMove {
        @Override
        public int maxTicks() {
            return 1;
        }
    }
}
