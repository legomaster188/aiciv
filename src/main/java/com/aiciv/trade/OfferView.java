package com.aiciv.trade;

import com.aiciv.world.ItemNames;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * One villager offer as the bot sees it: the current prices (demand and reputation included), what it gives and how
 * many uses are left. Item ids have no namespace; {@code costB} is empty when there is a single cost.
 */
public record OfferView(int index, String costA, int countA, String costB, int countB, String result, int resultCount,
                        int uses, int maxUses) {
    public boolean outOfStock() {
        return uses >= maxUses;
    }

    /** "1: 1 emerald -> 6 bread (10 left)", with "+ 2 book" for a second cost and "(sold out)" when none are left. */
    public String text() {
        return base() + " " + (outOfStock() ? "(sold out)" : "(" + (maxUses - uses) + " left)");
    }

    /** Like {@link #text()} without the number of uses left, for the snapshot; sold out is still marked. */
    String shortText() {
        return outOfStock() ? base() + " (sold out)" : base();
    }

    private String base() {
        String cost = countA + " " + costA + (costB.isEmpty() ? "" : " + " + countB + " " + costB);
        return index + ": " + cost + " -> " + resultCount + " " + result;
    }

    public static OfferView of(int index, MerchantOffer o) {
        ItemStack a = o.getCostA();
        ItemStack b = o.getCostB();
        return new OfferView(index, ItemNames.id(a), a.getCount(), b.isEmpty() ? "" : ItemNames.id(b), b.isEmpty() ? 0 : b.getCount(),
                ItemNames.id(o.getResult()), o.getResult().getCount(), o.getUses(), o.getMaxUses());
    }

    /** The index of the first in-stock offer whose result matches {@code want}, else -1. */
    public static int find(List<OfferView> offers, String want) {
        return match(offers, want, true);
    }

    /** The index of the first in-stock offer that takes {@code give} as its first cost (to sell it), else -1. */
    public static int findSelling(List<OfferView> offers, String give) {
        return match(offers, give, false);
    }

    /** An exact id match wins over a suffix ("sword" in "iron_sword") or a contained one, whatever the order. */
    private static int match(List<OfferView> offers, String query, boolean byResult) {
        String q = clean(query);
        if (q.isEmpty()) return -1;
        String singular = q.endsWith("s") ? q.substring(0, q.length() - 1) : q;
        for (int pass = 0; pass < 3; pass++) {
            for (OfferView o : offers) {
                if (o.outOfStock()) continue;
                String id = byResult ? o.result : o.costA;
                boolean hit = switch (pass) {
                    case 0 -> id.equals(q) || id.equals(singular);
                    case 1 -> id.endsWith("_" + q) || id.endsWith("_" + singular);
                    default -> id.contains(q) || id.contains(singular);
                };
                if (hit) return o.index;
            }
        }
        return -1;
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String q = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        return q.startsWith("minecraft:") ? q.substring("minecraft:".length()) : q;
    }
}
