package com.aiciv.perception;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SnapshotFormatter {
    public static final int MAX_CHARS = 6000;
    /** Inventory, blocks, players, mobs and remembered; the module sections' lists follow them. */
    private static final int FIXED_LISTS = 5;

    private SnapshotFormatter() {}

    /** Renders the snapshot, dropping the farthest entries of the longest lists until it fits. */
    public static String format(SnapshotData s, int maxChars) {
        List<List<String>> lists = new ArrayList<>(List.of(
                new ArrayList<>(s.inventory()),
                sightings(s.blocks()),
                sightings(s.players()),
                sightings(s.mobs()),
                new ArrayList<>(s.remembered())));
        for (SnapshotSection section : s.sections()) lists.add(new ArrayList<>(section.items()));
        int[] dropped = new int[lists.size()];
        String text = render(s, lists, dropped);
        while (text.length() > maxChars) {
            int longest = -1;
            for (int i = 0; i < lists.size(); i++) {
                if (!lists.get(i).isEmpty() && (longest < 0 || lists.get(i).size() > lists.get(longest).size())) longest = i;
            }
            if (longest < 0) break;
            List<String> l = lists.get(longest);
            l.remove(l.size() - 1);
            dropped[longest]++;
            text = render(s, lists, dropped);
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static List<String> sightings(List<Sighting> in) {
        List<String> out = new ArrayList<>();
        for (Sighting b : in) {
            out.add(b.count() > 1
                    ? String.format(Locale.ROOT, "%s x%d (nearest %dm %s)", b.label(), b.count(), b.distance(), b.direction())
                    : String.format(Locale.ROOT, "%s (%dm %s)", b.label(), b.distance(), b.direction()));
        }
        return out;
    }

    private static String render(SnapshotData s, List<List<String>> lists, int[] dropped) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "You: %s | pos %d %d %d | hp %d/%d food %d/20 xp %d | day %d, %s | biome %s",
                s.name(), s.x(), s.y(), s.z(), s.health(), s.maxHealth(), s.food(), s.xpLevel(), s.day(), s.timeOfDay(),
                s.biome()));
        text.append("\nHolding: ").append(s.holding())
                .append("\nWearing: ").append(s.equipment())
                .append("\nInventory: ").append(join(lists.get(0), dropped[0], "empty"))
                .append("\nBlocks nearby: ").append(join(lists.get(1), dropped[1], "nothing notable"))
                .append("\nPlayers nearby: ").append(join(lists.get(2), dropped[2], "nobody"))
                .append("\nMobs nearby: ").append(join(lists.get(3), dropped[3], "none"))
                .append("\nRemembered: ").append(join(lists.get(4), dropped[4], "nothing"));
        for (int i = 0; i < s.sections().size(); i++) {
            SnapshotSection section = s.sections().get(i);
            List<String> items = lists.get(FIXED_LISTS + i);
            int gone = dropped[FIXED_LISTS + i];
            if (items.isEmpty() && gone == 0 && section.emptyWord() == null) continue;
            text.append('\n').append(section.label()).append(": ").append(join(items, gone, section.emptyWord()));
        }
        return text.append("\nCurrent action: ").append(s.currentAction()).append(" (last: ").append(s.lastResult()).append(')')
                .toString();
    }

    private static String join(List<String> items, int dropped, String emptyWord) {
        if (items.isEmpty() && dropped == 0) return emptyWord;
        String joined = String.join(", ", items);
        if (dropped == 0) return joined;
        return (joined.isEmpty() ? "" : joined + ", ") + "+" + dropped + " more";
    }
}
