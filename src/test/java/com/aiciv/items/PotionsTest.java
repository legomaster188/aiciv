package com.aiciv.items;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PotionsTest {
    @Test
    void namesNormalise() {
        assertEquals(Optional.of("healing"), Potions.normalise("potion of healing"));
        assertEquals(Optional.of("swiftness"), Potions.normalise("Speed"));
        assertEquals(Optional.of("milk_bucket"), Potions.normalise("milk"));
        assertEquals(Optional.of("honey_bottle"), Potions.normalise("honey"));
        assertEquals(Optional.of("water"), Potions.normalise("water"));
        assertEquals(Optional.empty(), Potions.normalise("  "));
    }

    @Test
    void pearlsAreNotThrowable() {
        assertFalse(Potions.isThrowable("ender_pearl"));
        assertTrue(Potions.isThrowable("snowball"));
        assertTrue(Potions.isThrowable("splash_potion"));
        assertFalse(Potions.isThrowable("stone"));
    }

    @Test
    void throwSpecsFindTheItemAndPotion() {
        assertEquals(new Potions.ThrowSpec("splash_potion", "healing"), Potions.throwSpec("splash healing"));
        assertEquals(new Potions.ThrowSpec("splash_potion", "healing"), Potions.throwSpec("Splash Potion of Healing"));
        assertEquals(new Potions.ThrowSpec("lingering_potion", null), Potions.throwSpec("lingering_potion"));
        assertEquals(new Potions.ThrowSpec("snowball", null), Potions.throwSpec("snowball"));
        assertEquals(new Potions.ThrowSpec("experience_bottle", null), Potions.throwSpec("xp bottle"));
    }

    @Test
    void strongerPotionsCountAsTheirKind() {
        assertTrue(Potions.sameKind("strong_healing", "healing"));
        assertTrue(Potions.sameKind("long_swiftness", "swiftness"));
        assertFalse(Potions.sameKind("swiftness", "healing"));
    }
}
