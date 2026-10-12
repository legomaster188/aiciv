package com.aiciv.trade;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VillagerMemoryTest {
    private static final List<OfferView> OFFERS = List.of(
            new OfferView(0, "wheat", 20, "", 0, "emerald", 1, 0, 16),
            new OfferView(1, "emerald", 1, "", 0, "bread", 6, 0, 12),
            new OfferView(2, "emerald", 1, "", 0, "pumpkin_pie", 4, 0, 12),
            new OfferView(3, "emerald", 3, "", 0, "cookie", 18, 0, 12));

    @Test
    void describeListsTheFirstThreeOffersNearestFirst() {
        VillagerMemory m = new VillagerMemory();
        m.seen(UUID.randomUUID(), "librarian", new Pos(30, 64, 0), List.of(), 1);
        m.seen(UUID.randomUUID(), "farmer", new Pos(0, 64, -6), OFFERS, 2);
        List<String> lines = m.describe(new Pos(0, 64, 0), 5);
        assertEquals(2, lines.size());
        assertEquals("farmer 6m N: 0: 20 wheat -> 1 emerald; 1: 1 emerald -> 6 bread; 2: 1 emerald -> 4 pumpkin_pie", lines.get(0));
        assertEquals("librarian 30m E", lines.get(1));
    }

    @Test
    void describeHonoursTheLimit() {
        VillagerMemory m = new VillagerMemory();
        for (int i = 0; i < 4; i++) m.seen(UUID.randomUUID(), "farmer", new Pos(i * 3, 64, 0), List.of(), i);
        assertEquals(2, m.describe(new Pos(0, 64, 0), 2).size());
    }

    @Test
    void seeingTheSameVillagerAgainReplacesItsOffers() {
        VillagerMemory m = new VillagerMemory();
        UUID id = UUID.randomUUID();
        m.seen(id, "farmer", new Pos(0, 64, -6), OFFERS, 1);
        m.seen(id, "farmer", new Pos(0, 64, -6), OFFERS.subList(0, 1), 2);
        assertEquals(1, m.size());
        assertEquals(1, m.tags(new Pos(0, 64, 0)).stream().filter(t -> t.startsWith("offer:")).count());
    }

    @Test
    void capacityDropsTheOldestVillager() {
        VillagerMemory m = new VillagerMemory();
        UUID first = UUID.randomUUID();
        m.seen(first, "farmer", new Pos(0, 64, 0), List.of(), 0);
        for (int i = 1; i <= VillagerMemory.CAPACITY; i++) m.seen(UUID.randomUUID(), "mason", new Pos(i, 64, 0), List.of(), i);
        assertEquals(VillagerMemory.CAPACITY, m.size());
        assertTrue(m.tags(new Pos(0, 64, 0)).stream().noneMatch(t -> t.equals("villager:farmer")), "the oldest villager should be gone");
    }

    @Test
    void tagsNameTheProfessionAndEveryOffer() {
        VillagerMemory m = new VillagerMemory();
        m.seen(UUID.randomUUID(), "farmer", new Pos(0, 64, -6), OFFERS.subList(0, 2), 1);
        assertEquals(List.of("villager:farmer", "offer:farmer:0:emerald:wheat:20", "offer:farmer:1:bread:emerald:1"),
                m.tags(new Pos(0, 64, 0)));
    }
}
