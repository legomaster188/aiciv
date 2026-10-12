package com.aiciv.brain;

import com.aiciv.llm.ToolCall;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns a bot's situation into the short fixed list of choices a decision model picks from. */
public final class OptionMenu {
    public static final int MAX_OPTIONS = 40;
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");

    private static final Set<String> FOODS = Set.of("bread", "apple", "golden_apple", "carrot", "baked_potato", "potato",
            "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
            "cooked_salmon", "beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "melon_slice",
            "sweet_berries", "pumpkin_pie", "cookie", "dried_kelp", "beetroot");
    private static final String[] ARMOR = {"_helmet", "_chestplate", "_leggings", "_boots"};
    private static final String[][] COMPASS = {{"n", "N", "north"}, {"e", "E", "east"}, {"s", "S", "south"}, {"w", "W", "west"}};

    /**
     * What the scheduler knows beyond the snapshot. {@code craftableTargets} are items the bot could craft right now;
     * {@code mineableLabels} are block ids it has seen or remembered (logs, stone, ores).
     */
    public record MenuContext(Map<String, Integer> inventory, List<String> craftableTargets, boolean hungry,
                              List<String> hostileMobs, List<String> players, List<String> mineableLabels) {}

    private OptionMenu() {}

    /** At most {@value #MAX_OPTIONS} options with unique ids; always includes {@code wait} and four {@code explore_*}. */
    public static List<MenuOption> build(SnapshotData snap, MenuContext ctx) {
        Map<String, MenuOption> fixed = new LinkedHashMap<>();
        for (String[] d : COMPASS) {
            add(fixed, "explore_" + d[0], "walk 64 blocks " + d[2] + " to see what is there",
                    call("explore", "direction", d[1], "distance", 64));
        }
        add(fixed, "say_hello", "say hi in chat", call("say", "text", "Hi!"));
        add(fixed, "say_help", "ask in chat if anyone needs help", call("say", "text", "Anyone need help?"));
        add(fixed, "wait", "do nothing for now and think again later");

        Map<String, MenuOption> options = new LinkedHashMap<>();
        if (ctx.hungry() && ctx.inventory().keySet().stream().anyMatch(FOODS::contains)) {
            add(options, "eat", "eat food from your inventory", call("eat"));
        }
        for (String mob : ctx.hostileMobs()) {
            add(options, "attack_" + mob, "fight the nearby " + mob, call("attack", "target", mob));
        }
        for (String item : ctx.inventory().keySet()) {
            if (isArmor(item) && !snap.equipment().contains(item)) {
                add(options, "equip_" + item, "put on your " + item, call("equip", "item", item));
            }
        }
        for (String item : ctx.craftableTargets()) {
            add(options, "craft_" + item, "craft a " + item + " from what you carry", call("craft", "item", item, "count", 1));
        }
        int rawIron = ctx.inventory().getOrDefault("raw_iron", 0);
        if (rawIron > 0) {
            add(options, "smelt_raw_iron", "smelt " + rawIron + " raw_iron into iron_ingot (needs a furnace and fuel)",
                    call("smelt", "item", "raw_iron", "count", Math.min(rawIron, 64)));
        }
        for (String label : ctx.mineableLabels()) {
            int count = mineCount(label);
            if (count > 0) add(options, "mine_" + label, "mine " + count + " " + label, call("mine", "block", label, "count", count));
        }
        // Trees are rarely in sight from where a bot stands; mine finds the nearest ones itself.
        add(options, "mine_log", "chop 6 logs from the nearest trees (any kind)", call("mine", "block", "log", "count", 6));
        for (String player : ctx.players()) {
            add(options, "go_to_" + player, "walk over to " + player, call("goToPlayer", "name", player));
            add(options, "follow_" + player, "follow " + player + " for a minute", call("follow", "name", player, "seconds", 60));
        }

        List<MenuOption> out = new ArrayList<>(options.values());
        while (out.size() > MAX_OPTIONS - fixed.size()) out.remove(out.size() - 1);
        out.addAll(fixed.values());
        return out;
    }

    private static int mineCount(String label) {
        if (label.endsWith("_log")) return 6;
        if (label.equals("stone") || label.equals("cobblestone")) return 8;
        if (label.endsWith("_ore")) return 3;
        return 0;
    }

    private static boolean isArmor(String item) {
        for (String suffix : ARMOR) if (item.endsWith(suffix)) return true;
        return false;
    }

    private static void add(Map<String, MenuOption> into, String rawId, String description, ToolCall... calls) {
        String id = rawId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (id.length() > 40) id = id.substring(0, 40);
        if (!ID.matcher(id).matches() || into.containsKey(id)) return;
        into.put(id, new MenuOption(id, description, List.of(calls)));
    }

    /** key, value, key, value...; values are strings or whole numbers. */
    private static ToolCall call(String name, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return ToolCall.of(name, args);
    }
}
