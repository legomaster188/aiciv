package com.aiciv.trade;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class OfferViewTest {
    private static OfferView offer(int index, String costA, int countA, String result, int resultCount, int uses, int maxUses) {
        return new OfferView(index, costA, countA, "", 0, result, resultCount, uses, maxUses);
    }

    @Test
    void textWithOneCost() {
        assertEquals("1: 1 emerald -> 6 bread (10 left)", offer(1, "emerald", 1, "bread", 6, 2, 12).text());
    }

    @Test
    void textWithTwoCosts() {
        OfferView o = new OfferView(0, "emerald", 5, "book", 2, "enchanted_book", 1, 0, 12);
        assertEquals("0: 5 emerald + 2 book -> 1 enchanted_book (12 left)", o.text());
    }

    @Test
    void soldOutOffer() {
        OfferView o = offer(2, "emerald", 1, "bread", 6, 12, 12);
        assertTrue(o.outOfStock());
        assertEquals("2: 1 emerald -> 6 bread (sold out)", o.text());
    }

    @Test
    void findSkipsSoldOutOffers() {
        List<OfferView> offers = List.of(offer(0, "emerald", 1, "bread", 6, 12, 12), offer(1, "emerald", 2, "bread", 6, 0, 12));
        assertEquals(1, OfferView.find(offers, "bread"));
    }

    @Test
    void findMatchesExactBeforeSuffixAndContains() {
        List<OfferView> offers = List.of(offer(0, "emerald", 1, "iron_sword", 1, 0, 12), offer(1, "emerald", 1, "sword", 1, 0, 12));
        assertEquals(1, OfferView.find(offers, "sword"));
        assertEquals(0, OfferView.find(offers, "Iron Sword"));
        assertEquals(0, OfferView.find(offers, "minecraft:iron_sword"));
    }

    @Test
    void findAcceptsAPlural() {
        assertEquals(0, OfferView.find(List.of(offer(0, "emerald", 1, "bread", 6, 0, 12)), "breads"));
    }

    @Test
    void findReturnsMinusOneForAnUnknownItem() {
        assertEquals(-1, OfferView.find(List.of(offer(0, "emerald", 1, "bread", 6, 0, 12)), "diamond"));
        assertEquals(-1, OfferView.find(List.of(), "bread"));
    }

    @Test
    void findSellingMatchesTheCost() {
        List<OfferView> offers = List.of(offer(0, "emerald", 1, "bread", 6, 0, 12), offer(1, "wheat", 20, "emerald", 1, 0, 16));
        assertEquals(1, OfferView.findSelling(offers, "wheat"));
        assertEquals(-1, OfferView.findSelling(offers, "carrot"));
    }

    @Test
    void findSellingSkipsSoldOutOffers() {
        List<OfferView> offers = List.of(offer(0, "wheat", 20, "emerald", 1, 16, 16), offer(1, "wheat", 30, "emerald", 1, 0, 16));
        assertEquals(1, OfferView.findSelling(offers, "wheat"));
    }
}
