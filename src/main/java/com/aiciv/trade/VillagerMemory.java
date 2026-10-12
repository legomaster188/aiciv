package com.aiciv.trade;

import com.aiciv.nav.Pos;
import com.aiciv.perception.Compass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The villagers a bot has met and what they offered (prices as last seen). A per-bot attachment; server thread. */
public final class VillagerMemory {
    public static final int CAPACITY = 12;
    private static final int OFFERS_SHOWN = 3;

    private record Entry(String profession, Pos pos, List<OfferView> offers, long tick) {}

    /** Insertion order is least recently seen first; seeing a villager again moves it to the end. */
    private final Map<UUID, Entry> villagers = new LinkedHashMap<>();

    public void seen(UUID id, String profession, Pos pos, List<OfferView> offers, long tick) {
        villagers.remove(id);
        villagers.put(id, new Entry(profession, pos, List.copyOf(offers), tick));
        while (villagers.size() > CAPACITY) villagers.remove(villagers.keySet().iterator().next());
    }

    public int size() {
        return villagers.size();
    }

    public boolean knows(UUID id) {
        return villagers.containsKey(id);
    }

    /** "farmer 6m N: 0: 20 wheat -> 1 emerald; 1: ..." (first three offers), nearest first; no colon without offers. */
    public List<String> describe(Pos from, int n) {
        List<String> out = new ArrayList<>();
        for (Entry e : nearestFirst(from)) {
            if (out.size() >= n) break;
            StringBuilder line = new StringBuilder(where(e, from));
            for (int i = 0; i < Math.min(OFFERS_SHOWN, e.offers.size()); i++) {
                line.append(i == 0 ? ": " : "; ").append(e.offers.get(i).shortText());
            }
            out.add(line.toString());
        }
        return out;
    }

    /** {@code villager:<profession>} per villager, then {@code offer:<profession>:<index>:<result>:<costA>:<countA>} per offer. */
    public List<String> tags(Pos from) {
        List<String> out = new ArrayList<>();
        for (Entry e : nearestFirst(from)) {
            out.add("villager:" + e.profession);
            for (OfferView o : e.offers) {
                out.add("offer:" + e.profession + ":" + o.index() + ":" + o.result() + ":" + o.costA() + ":" + o.countA());
            }
        }
        return out.stream().distinct().toList();
    }

    private List<Entry> nearestFirst(Pos from) {
        return villagers.values().stream().sorted(Comparator.comparingDouble(e -> distance(e.pos, from))).toList();
    }

    private static String where(Entry e, Pos from) {
        return e.profession + " " + Math.round(distance(e.pos, from)) + "m " + Compass.direction(e.pos.x() - from.x(), e.pos.z() - from.z());
    }

    /** "farmer 6m N" for a villager seen now. */
    static String where(String profession, Pos pos, Pos from) {
        return where(new Entry(profession, pos, List.of(), 0), from);
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
