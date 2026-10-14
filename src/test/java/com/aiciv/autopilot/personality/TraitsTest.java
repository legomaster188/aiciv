package com.aiciv.autopilot.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraitsTest {
    @Test
    void traitsAreClamped() {
        Traits t = new Traits(3.0, -7, Double.NaN, 0.5, -1);
        assertEquals(1.0, t.brave());
        assertEquals(-1.0, t.greedy());
        assertEquals(0.0, t.social());
        assertEquals(0.5, t.tidy());
        assertEquals(-1.0, t.patient());
    }

    @Test
    void asMapKeepsTheOrder() {
        assertEquals("[brave, greedy, social, tidy, patient]", Traits.NEUTRAL.asMap().keySet().toString());
    }
}
