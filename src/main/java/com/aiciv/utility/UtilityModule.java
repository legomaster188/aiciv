package com.aiciv.utility;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/** Sleep, doors and switches, enchanting and brewing. */
public final class UtilityModule implements ToolModule {
    static final String NIGHT_LINE = "it is night: sleep in a bed to skip it";
    static final String CAN_SLEEP = "can_sleep";
    private static final List<String> ENCHANTABLE = List.of("diamond_sword", "diamond_pickaxe", "iron_sword", "iron_pickaxe",
            "iron_chestplate", "bow");
    private static final int SIGHT_RADIUS = 16;

    @Override
    public String name() {
        return "utility";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject xyz = Tools.props("x", "integer", "block x");
        xyz.add("y", Tools.prop("integer", "block y"));
        xyz.add("z", Tools.prop("integer", "block z"));
        JsonObject enchant = Tools.props("item", "string", "the item you hold to enchant, e.g. iron_pickaxe");
        enchant.add("option", Tools.prop("integer", "which of the table's 3 offers, 1 (cheapest) to 3; default 1"));
        JsonObject brew = Tools.props("potion", "string", "awkward, swiftness, strength, healing, fire_resistance, night_vision, water_breathing, regeneration, leaping or slow_falling");
        brew.add("count", Tools.prop("integer", "bottles to brew, 1 to 3; default 1"));
        return List.of(
                Tools.fn("sleep", "Sleep in a nearby bed (places a bed you carry) until morning. Only works at night or in a thunderstorm.", null),
                Tools.fn("use_block", "Press a button, flip a lever, open or close a door, trapdoor or fence gate, ring a bell, or tune a repeater, comparator or daylight detector at x y z.", xyz, "x", "y", "z"),
                Tools.fn("enchant", "Enchant a held item at an enchanting table nearby. Costs lapis lazuli and experience levels equal to the option.", enchant, "item"),
                Tools.fn("brew", "Brew potions at a brewing stand: awkward potions first, then real ones. Needs blaze_powder fuel, bottles and the ingredient.", brew, "potion"));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "sleep":
                return new ActionFactory.Result.Ok(new SleepAction());
            case "useblock": {
                for (String axis : new String[] {"x", "y", "z"}) {
                    if (Args.integer(args, axis) == null) return invalid("use_block needs a whole number \"" + axis + "\"");
                }
                String problem = Args.posProblem("use_block", args);
                if (problem != null) return invalid(problem);
                return new ActionFactory.Result.Ok(new UseBlockAction(Args.integer(args, "x"), Args.integer(args, "y"), Args.integer(args, "z")));
            }
            case "enchant": {
                String item = Args.str(args, "item");
                if (item == null || item.isBlank()) return invalid("enchant needs an \"item\" name, e.g. iron_pickaxe");
                return new ActionFactory.Result.Ok(new EnchantAction(item, Args.intIn(args, "option", 1, 1, 3)));
            }
            case "brew": {
                String potion = Args.str(args, "potion");
                if (potion == null || potion.isBlank()) return invalid("brew needs a \"potion\" name, e.g. awkward or swiftness");
                return new ActionFactory.Result.Ok(new BrewAction(potion, Args.intIn(args, "count", 1, 1, 3)));
            }
            default:
                return null;
        }
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Utility: sleep at night (places a carried bed); use_block presses buttons, flips levers, opens doors, tunes repeaters; "
                + "enchant at a table with lapis and levels; brew awkward potions then real ones. You open doors on your way automatically.");
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        ServerLevel level = bot.level();
        if (!SleepAction.canSleep(level, bot.blockPosition())) return List.of(); // the bed rule: night, and no exploding beds
        return List.of(new SnapshotSection("Rest", List.of(NIGHT_LINE), null, List.of(CAN_SLEEP)));
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<MenuOption> out = new ArrayList<>();
        Map<String, Integer> inv = ctx.inventory();
        boolean night = snap.sections().stream().anyMatch(s -> s.tags().contains(CAN_SLEEP));
        if (night && (sees(snap, "_bed", true) || inv.keySet().stream().anyMatch(i -> i.endsWith("_bed")))) {
            out.add(new MenuOption("sleep", "sleep in the bed to skip the night", List.of(call("sleep"))));
        }
        if (sees(snap, "enchanting_table", false) && inv.getOrDefault("lapis_lazuli", 0) > 0) {
            for (String item : ENCHANTABLE) {
                if (!inv.containsKey(item)) continue;
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                args.addProperty("option", 1);
                out.add(new MenuOption("enchant_" + item, "enchant your " + item + " at the table (1 lapis, 1 level)", List.of(ToolCall.of("enchant", args))));
                break;
            }
        }
        if (sees(snap, "brewing_stand", false) && inv.containsKey("nether_wart") && inv.containsKey("blaze_powder")
                && (inv.containsKey("glass_bottle") || inv.containsKey("potion"))) {
            JsonObject args = new JsonObject();
            args.addProperty("potion", "awkward");
            args.addProperty("count", 1);
            out.add(new MenuOption("brew_awkward", "brew an awkward potion at the brewing stand", List.of(ToolCall.of("brew", args))));
        }
        return out;
    }

    /** A sighting within the search radius whose label equals (or, with {@code suffix}, ends with) {@code label}. */
    private static boolean sees(SnapshotData snap, String label, boolean suffix) {
        for (Sighting s : snap.blocks()) {
            if (s.distance() <= SIGHT_RADIUS && (suffix ? s.label().endsWith(label) : s.label().equals(label))) return true;
        }
        return false;
    }

    private static ToolCall call(String name) {
        return ToolCall.of(name, new JsonObject());
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(new Sighting("white_bed", 1, 3, "north")),
                List.of(new SnapshotSection("Rest", List.of(NIGHT_LINE), null, List.of(CAN_SLEEP))));
    }
}
