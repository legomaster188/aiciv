package com.aiciv.items;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompassReadingTest {
    @Test
    void pointsInTheDirectionOfTheTarget() {
        assertEquals("compass points SW (world spawn)", CompassReading.line("spawn", -10, 10));
        assertEquals("compass points N (lodestone)", CompassReading.line("lodestone", 0, -5));
        assertEquals("recovery_compass points E (where you died)", CompassReading.line("recovery", 7, 0));
    }

    @Test
    void standingOnTheTargetSaysNothing() {
        assertNull(CompassReading.line("spawn", 0, 0));
    }
}
