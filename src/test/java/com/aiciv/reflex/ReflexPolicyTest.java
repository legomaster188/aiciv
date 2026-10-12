package com.aiciv.reflex;

import static com.aiciv.reflex.ReflexPolicy.Reflex.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aiciv.reflex.ReflexPolicy.Snapshot;
import org.junit.jupiter.api.Test;

class ReflexPolicyTest {
    /** A healthy, fed bot on dry land with a sword, which nothing is bothering. */
    static Snapshot calm() {
        return new Snapshot(false, 300, 300, 20f, 20, true, false, true, false);
    }

    @Test
    void nothingAppliesMeansNone() {
        assertEquals(NONE, ReflexPolicy.decide(calm()));
    }

    @Test
    void lavaOrFireBeatsEverything() {
        Snapshot s = new Snapshot(true, 10, 300, 3f, 2, true, true, true, false);
        assertEquals(ESCAPE_HAZARD, ReflexPolicy.decide(s));
    }

    @Test
    void lowAirBeatsFleeingFightingAndEating() {
        Snapshot s = new Snapshot(false, 99, 300, 3f, 2, true, true, true, false);
        assertEquals(SURFACE, ReflexPolicy.decide(s));
    }

    @Test
    void airExactlyAThirdIsStillFine() {
        Snapshot s = new Snapshot(false, 100, 300, 20f, 20, true, false, true, false);
        assertEquals(NONE, ReflexPolicy.decide(s));
    }

    @Test
    void lowHealthWhileAttackedFlees() {
        Snapshot s = new Snapshot(false, 300, 300, 6f, 2, true, true, true, false);
        assertEquals(FLEE, ReflexPolicy.decide(s));
    }

    @Test
    void lowHealthWithoutAnAttackerDoesNotFlee() {
        Snapshot s = new Snapshot(false, 300, 300, 4f, 20, true, false, true, false);
        assertEquals(NONE, ReflexPolicy.decide(s));
    }

    @Test
    void attackedWhileArmedFightsBack() {
        Snapshot s = new Snapshot(false, 300, 300, 7f, 2, true, true, true, false);
        assertEquals(FIGHT_BACK, ReflexPolicy.decide(s));
    }

    @Test
    void attackedWithEmptyHandsDoesNotFightBack() {
        Snapshot s = new Snapshot(false, 300, 300, 20f, 20, true, true, false, false);
        assertEquals(NONE, ReflexPolicy.decide(s));
    }

    @Test
    void hungryWithFoodEats() {
        Snapshot s = new Snapshot(false, 300, 300, 20f, 12, true, false, true, false);
        assertEquals(EAT, ReflexPolicy.decide(s));
        assertEquals(NONE, ReflexPolicy.decide(new Snapshot(false, 300, 300, 20f, 13, true, false, true, false)));
    }

    @Test
    void hungryButNoFoodIsNone() {
        Snapshot s = new Snapshot(false, 300, 300, 20f, 3, false, false, true, false);
        assertEquals(NONE, ReflexPolicy.decide(s));
    }

    @Test
    void anotherActionAlreadyEatingMeansNoSecondMeal() {
        Snapshot s = new Snapshot(false, 300, 300, 20f, 3, true, false, true, true);
        assertEquals(NONE, ReflexPolicy.decide(s));
    }
}
