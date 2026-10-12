package com.aiciv.farm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnimalFoodTest {
    @Test
    void cowEatsWheat() {
        assertEquals(List.of("wheat"), AnimalFood.foods("cow"));
        assertEquals(List.of("wheat"), AnimalFood.foods("sheep"));
    }

    @Test
    void pigEatsCarrotsPotatoesAndBeetroot() {
        assertTrue(AnimalFood.foods("pig").contains("carrot"));
        assertFalse(AnimalFood.foods("pig").contains("wheat"));
    }

    @Test
    void wolvesEatMeatAndCatsFish() {
        assertTrue(AnimalFood.foods("wolf").contains("beef"));
        assertEquals(List.of("cod", "salmon"), AnimalFood.foods("cat"));
    }

    @Test
    void unknownAnimalsEatNothingListed() {
        assertTrue(AnimalFood.foods("zombie").isEmpty());
        assertTrue(AnimalFood.foods(null).isEmpty());
    }
}
