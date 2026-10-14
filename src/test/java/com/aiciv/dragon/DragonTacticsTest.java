package com.aiciv.dragon;

import static com.aiciv.dragon.DragonTactics.Move.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aiciv.dragon.DragonTactics.Situation;
import org.junit.jupiter.api.Test;

class DragonTacticsTest {
    /** A quiet fight: nothing in breath, no crystals, dragon flying, a bow with arrows, plenty of blocks, full health. */
    private static Situation base() {
        return new Situation(false, 0, 0, false, 40, false, true, 32, 64, 20f);
    }

    private static Situation with(boolean inBreath, int open, int caged, boolean perched, boolean dead, boolean bow, int arrows,
                                  int throwaways, float health) {
        return new Situation(inBreath, open, caged, perched, 10, dead, bow, arrows, throwaways, health);
    }

    @Test
    void aDeadDragonEndsTheFightEvenInBreath() {
        assertEquals(DONE, DragonTactics.next(with(true, 3, 1, true, true, true, 32, 64, 20f)));
    }

    @Test
    void breathBeatsCrystalsAndEverythingElse() {
        assertEquals(DODGE, DragonTactics.next(with(true, 3, 1, true, false, true, 32, 64, 20f)));
    }

    @Test
    void openCrystalsAreShotWithABowAndArrows() {
        assertEquals(SHOOT_CRYSTAL, DragonTactics.next(with(false, 2, 0, true, false, true, 1, 0, 20f)));
        assertEquals(HIT_HEAD, DragonTactics.next(with(false, 2, 0, true, false, false, 0, 0, 20f)));
        assertEquals(HIT_HEAD, DragonTactics.next(with(false, 2, 0, true, false, true, 0, 0, 20f)));
    }

    @Test
    void cagesNeedBlocksArrowsAndHealth() {
        assertEquals(BREAK_CAGE, DragonTactics.next(with(false, 0, 1, false, false, true, 8, 24, 13f)));
        assertEquals(SHOOT_DRAGON, DragonTactics.next(with(false, 0, 1, false, false, true, 8, 23, 20f)));
        assertEquals(SHOOT_DRAGON, DragonTactics.next(with(false, 0, 1, false, false, true, 8, 64, 12f)));
        assertEquals(WAIT, DragonTactics.next(with(false, 0, 1, false, false, false, 0, 64, 20f)));
    }

    @Test
    void openCrystalsComeBeforeCages() {
        assertEquals(SHOOT_CRYSTAL, DragonTactics.next(with(false, 1, 2, false, false, true, 8, 64, 20f)));
    }

    @Test
    void aPerchedDragonIsHitBeforeItIsShot() {
        assertEquals(HIT_HEAD, DragonTactics.next(with(false, 0, 0, true, false, true, 8, 64, 20f)));
    }

    @Test
    void aFlyingDragonIsShotWithABowAndOtherwiseWaitedFor() {
        assertEquals(SHOOT_DRAGON, DragonTactics.next(base()));
        assertEquals(WAIT, DragonTactics.next(with(false, 0, 0, false, false, true, 0, 64, 20f)));
        assertEquals(WAIT, DragonTactics.next(with(false, 0, 0, false, false, false, 32, 64, 20f)));
    }
}
