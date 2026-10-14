package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aiciv.autopilot.Tuning;
import org.junit.jupiter.api.Test;

class BehaviourTest {
    private static Tuning of(double brave, double greedy, double social, double tidy, double patient) {
        return Behaviour.tuning(new Traits(brave, greedy, social, tidy, patient));
    }

    private static Tuning all(double v) {
        return of(v, v, v, v, v);
    }

    @Test
    void maxRetries() {
        assertEquals(1, all(-1).maxRetries());
        assertEquals(3, all(0).maxRetries());
        assertEquals(5, all(1).maxRetries());
    }

    @Test
    void exploreDistance() {
        assertEquals(32, all(-1).exploreDistance());
        assertEquals(32, all(0).exploreDistance());
        assertEquals(64, all(1).exploreDistance());
    }

    @Test
    void retreatHealth() {
        assertEquals(14, all(-1).retreatHealth());
        assertEquals(10, all(0).retreatHealth());
        assertEquals(6, all(1).retreatHealth());
    }

    @Test
    void hysteresis() {
        assertEquals(0.0, all(-1).hysteresis(), 1e-9);
        assertEquals(0.15, all(0).hysteresis(), 1e-9);
        assertEquals(0.30, all(1).hysteresis(), 1e-9);
    }

    @Test
    void homeAtNight() {
        assertTrue(all(-1).homeAtNight());
        assertFalse(all(0).homeAtNight());
        assertTrue(all(1).homeAtNight());
        assertFalse(of(0, 0, 0, 0.29, 0).homeAtNight());
        assertTrue(of(0, 0, 0, 0.3, 0).homeAtNight());
        assertTrue(of(-0.3, 0, 0, 0, 0).homeAtNight());
    }

    @Test
    void keepCount() {
        assertEquals(0, all(-1).keepCount());
        assertEquals(16, all(0).keepCount());
        assertEquals(48, all(1).keepCount());
        assertEquals(0, of(0, -0.5, 0, 0, 0).keepCount());
    }

    @Test
    void helpsWhenAsked() {
        assertFalse(all(-1).helpsWhenAsked());
        assertTrue(all(0).helpsWhenAsked());
        assertTrue(of(0, 0, -0.5, 0, 0).helpsWhenAsked());
    }

    @Test
    void chatCooldownFactor() {
        assertEquals(1.5, all(-1).chatCooldownFactor(), 1e-9);
        assertEquals(1.0, all(0).chatCooldownFactor(), 1e-9);
        assertEquals(0.5, all(1).chatCooldownFactor(), 1e-9);
    }

    @Test
    void depositWhenFree() {
        assertEquals(0, all(-1).depositWhenFree());
        assertEquals(4, all(0).depositWhenFree());
        assertEquals(9, all(1).depositWhenFree());
        assertEquals(4, of(0, 0, 0, 0.49, 0).depositWhenFree());
    }
}
