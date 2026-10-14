package com.aiciv.autopilot;

import com.aiciv.modules.ToolModules;
import com.aiciv.perception.Sighting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything an autopilot decision may read, copied out of the game on the server thread. Pure: no world access.
 * {@code actions} holds normalised action keys ({@link com.aiciv.actions.ActionFactory#knownKeys()}).
 */
public record Situation(String name, String dimension, int x, int y, int z, int health, int food, boolean night, long tick,
                        Map<String, Integer> inventory, Set<String> worn, String holding, int freeSlots,
                        List<Sighting> blocks, List<Sighting> players, List<Sighting> mobs, List<String> hostiles,
                        List<String> remembered, Set<String> tags, List<String> structures, Set<String> actions,
                        String currentAction, boolean busy, Set<String> flags, Map<String, Long> lastDone,
                        List<AutopilotMemory.Request> requests, Tuning tuning, String pinnedGoal) {

    /** The same set as the menu's food list ({@code OptionMenu.FOODS}). */
    public static final Set<String> FOODS = Set.of("bread", "apple", "golden_apple", "carrot", "baked_potato", "potato",
            "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
            "cooked_salmon", "beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "melon_slice",
            "sweet_berries", "pumpkin_pie", "cookie", "dried_kelp", "beetroot");
    public static final List<String> RAW_FOODS = List.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato");

    private static final List<String> TOOL_KINDS = List.of("pickaxe", "axe", "sword", "shovel", "hoe");
    private static final List<String> ARMOR_PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    private static final Pattern STRUCTURE = Pattern.compile("^(\\S+) by (\\S+) at (-?\\d+) (-?\\d+) (-?\\d+)");
    private static final int PLAYER_RANGE = 32;

    public Situation {
        inventory = inventory == null ? Map.of() : inventory;
        worn = worn == null ? Set.of() : worn;
        blocks = blocks == null ? List.of() : blocks;
        players = players == null ? List.of() : players;
        mobs = mobs == null ? List.of() : mobs;
        hostiles = hostiles == null ? List.of() : hostiles;
        remembered = remembered == null ? List.of() : remembered;
        tags = tags == null ? Set.of() : tags;
        structures = structures == null ? List.of() : structures;
        actions = actions == null ? Set.of() : actions;
        flags = flags == null ? Set.of() : flags;
        lastDone = lastDone == null ? Map.of() : lastDone;
        requests = requests == null ? List.of() : requests;
        tuning = tuning == null ? Tuning.DEFAULT : tuning;
    }

    // ---------------------------------------------------------------- inventory

    public int count(String id) {
        return inventory.getOrDefault(id, 0);
    }

    /** The exact id's count when it is held, else the sum over ids containing {@code query} (a trailing "s" removed). */
    public int countLike(String query) {
        if (inventory.containsKey(query)) return count(query);
        String q = query.endsWith("s") ? query.substring(0, query.length() - 1) : query;
        if (q.isEmpty()) return 0;
        int n = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) if (e.getKey().contains(q)) n += e.getValue();
        return n;
    }

    public int logs() {
        int n = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getKey().endsWith("_log") || e.getKey().endsWith("_stem")) n += e.getValue();
        }
        return n;
    }

    public int planks() {
        int n = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) if (e.getKey().endsWith("_planks")) n += e.getValue();
        return n;
    }

    /** Logs plus planks, four planks counting as one log. */
    public int logEquivalents() {
        return logs() + planks() / 4;
    }

    // ---------------------------------------------------------------- tools and armor

    public static int toolTierOf(String material) {
        return switch (material) {
            case "wooden", "golden" -> 1;
            case "stone", "copper" -> 2;
            case "iron" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> 0;
        };
    }

    private static int armorTierOf(String material) {
        return switch (material) {
            case "leather" -> 1;
            case "golden", "copper" -> 2;
            case "chainmail", "iron" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> 0;
        };
    }

    /** The best tier among held and carried {@code <material>_<kind>} items (kind: pickaxe, axe, sword, shovel, hoe). */
    public int toolTier(String kind) {
        int best = holding == null ? 0 : tierOf(holding, kind);
        for (String id : inventory.keySet()) best = Math.max(best, tierOf(id, kind));
        return best;
    }

    private static int tierOf(String id, String kind) {
        String suffix = "_" + kind;
        return id.endsWith(suffix) ? toolTierOf(id.substring(0, id.length() - suffix.length())) : 0;
    }

    /** Tools count when the bot has one of at least that tier; anything else when it carries one. */
    public boolean hasTool(String item) {
        for (String kind : TOOL_KINDS) {
            int tier = tierOf(item, kind);
            if (tier > 0) return toolTier(kind) >= tier;
        }
        return count(item) > 0;
    }

    /** The tier of the worn piece (helmet, chestplate, leggings, boots), 0 when none. */
    public int wornTier(String piece) {
        String suffix = "_" + piece;
        int best = 0;
        for (String id : worn) {
            if (id.endsWith(suffix)) best = Math.max(best, armorTierOf(id.substring(0, id.length() - suffix.length())));
        }
        return best;
    }

    /** How many of the four armor pieces worn reach {@code tier}. */
    public int wornAtLeast(int tier) {
        int n = 0;
        for (String piece : ARMOR_PIECES) if (wornTier(piece) >= tier) n++;
        return n;
    }

    public int foodItems() {
        int n = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) if (FOODS.contains(e.getKey())) n += e.getValue();
        return n;
    }

    // ---------------------------------------------------------------- surroundings

    /** A block sighting with that label within {@code within} blocks. */
    public boolean sees(String label, int within) {
        for (Sighting s : blocks) if (s.label().equals(label) && s.distance() <= within) return true;
        return false;
    }

    /** Seen within 24 blocks, or remembered farther away. */
    public boolean knows(String label) {
        if (sees(label, 24)) return true;
        String prefix = label + " at ";
        for (String r : remembered) if (r.startsWith(prefix)) return true;
        return false;
    }

    /** A bed carried or seen within 16 blocks: what the sleep action can use. */
    public boolean bedKnown() {
        for (Map.Entry<String, Integer> e : inventory.entrySet()) if (e.getKey().endsWith("_bed") && e.getValue() > 0) return true;
        for (Sighting s : blocks) if (s.label().endsWith("_bed") && s.distance() <= 16) return true;
        return false;
    }

    public boolean tableNear() {
        return sees("crafting_table", 4) || count("crafting_table") > 0;
    }

    public boolean furnaceNear() {
        return sees("furnace", 16) || count("furnace") > 0;
    }

    /** How many of that mob are seen when the nearest is within {@code within}, else 0. */
    public int mobCount(String label, int within) {
        for (Sighting s : mobs) if (s.label().equals(label) && s.distance() <= within) return s.count();
        return 0;
    }

    public Optional<Sighting> nearestHostile(int within) {
        Sighting best = null;
        for (Sighting s : mobs) {
            if (s.distance() > within || !hostiles.contains(s.label())) continue;
            if (best == null || s.distance() < best.distance()) best = s;
        }
        return Optional.ofNullable(best);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public List<String> tagsWithPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        for (String t : tags) if (t.startsWith(prefix)) out.add(t);
        out.sort(null);
        return out;
    }

    // ---------------------------------------------------------------- home

    /** The bot's home structure name: its name lower-cased plus "_home", cut to 32 characters. */
    public static String homeName(String bot) {
        String n = bot.toLowerCase(Locale.ROOT) + "_home";
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    /** The home's anchor x, y, z from the Structures section, when one was built. */
    public Optional<int[]> home() {
        String prefix = homeName(name) + " by ";
        for (String line : structures) {
            if (!line.startsWith(prefix)) continue;
            Matcher m = STRUCTURE.matcher(line);
            if (m.find()) {
                return Optional.of(new int[] {Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))});
            }
        }
        return Optional.empty();
    }

    public double distanceTo(int[] p) {
        double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- actions, places, memory

    public boolean hasAction(String name) {
        return actions.contains(ToolModules.normalise(name));
    }

    public boolean inOverworld() {
        return "overworld".equals(dimension);
    }

    public boolean inNether() {
        return "the_nether".equals(dimension);
    }

    public boolean inEnd() {
        return "the_end".equals(dimension);
    }

    public boolean flag(String f) {
        return flags.contains(f);
    }

    /** Whether "goal/step" finished at most {@code ticks} ago. */
    public boolean doneWithin(String goalSlashStep, long ticks) {
        Long at = lastDone.get(goalSlashStep);
        return at != null && at >= tick - ticks;
    }

    public List<String> humans() {
        return playersTagged(" [human]");
    }

    public List<String> aiPlayers() {
        return playersTagged(" [AI]");
    }

    private List<String> playersTagged(String tag) {
        List<String> out = new ArrayList<>();
        for (Sighting s : players) {
            if (s.distance() <= PLAYER_RANGE && s.label().endsWith(tag)) out.add(s.label().substring(0, s.label().length() - tag.length()));
        }
        return out;
    }
}
