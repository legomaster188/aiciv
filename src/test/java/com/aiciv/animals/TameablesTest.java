package com.aiciv.animals;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TameablesTest {
    @Test
    void feedAndRideMethods() {
        for (String a : List.of("wolf", "cat", "parrot")) assertEquals(Optional.of(Tameables.Method.FEED), Tameables.method(a), a);
        for (String a : List.of("horse", "donkey", "mule", "llama", "trader_llama")) {
            assertEquals(Optional.of(Tameables.Method.RIDE), Tameables.method(a), a);
        }
    }

    @Test
    void otherAnimalsAreNotTameable() {
        assertEquals(Optional.empty(), Tameables.method("fox"));
        assertEquals(Optional.empty(), Tameables.method("cow"));
        assertEquals(Optional.empty(), Tameables.method(null));
    }

    @Test
    void itemsPerAnimal() {
        assertEquals(List.of("bone"), Tameables.items("wolf"));
        assertEquals(List.of("cod", "salmon"), Tameables.items("cat"));
        assertEquals(List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod"),
                Tameables.items("parrot"));
        assertEquals(List.of(), Tameables.items("horse"));
        assertEquals(List.of(), Tameables.items("fox"));
    }

    @Test
    void pluralsReadNaturally() {
        assertEquals("wolves", Tameables.plural("wolf"));
        assertEquals("cats", Tameables.plural("cat"));
    }
}
