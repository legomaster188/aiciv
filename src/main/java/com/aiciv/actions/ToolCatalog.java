package com.aiciv.actions;

import static com.aiciv.modules.Tools.fn;
import static com.aiciv.modules.Tools.prop;
import static com.aiciv.modules.Tools.props;

import com.aiciv.modules.ToolModules;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The actions offered to models, in OpenAI function-calling format. */
public final class ToolCatalog {
    /**
     * How much of the catalog a model is sent (aiciv.json {@code toolCatalog}). FULL: every tool. GATED: the core tools
     * and the modules {@link com.aiciv.modules.ToolModule#relevant relevant} to the bot's situation. COMPACT: gated, without
     * the parameter descriptions, for providers whose per-request token limit even the gated catalog breaks.
     */
    public enum Mode {
        FULL, GATED, COMPACT;

        /** The mode a config value names ("full", "gated", "compact", any case), or null. */
        public static Mode parse(String value) {
            if (value == null) return null;
            for (Mode m : values()) if (m.name().equalsIgnoreCase(value.trim())) return m;
            return null;
        }
    }

    private ToolCatalog() {}

    /** Every tool: the core ones and every module's. */
    public static JsonArray tools() {
        JsonArray tools = core();
        for (JsonObject t : ToolModules.tools()) tools.add(t);
        return tools;
    }

    /** The tools to send for a bot in this situation; {@code ctx} may be null. */
    public static JsonArray tools(Mode mode, SnapshotData snap, BotContext ctx) {
        if (mode == Mode.FULL) return tools();
        JsonArray tools = core();
        for (JsonObject t : ToolModules.tools(snap, ctx)) tools.add(t);
        return mode == Mode.COMPACT ? compact(tools) : tools;
    }

    /** A copy of {@code tools} without the parameters' descriptions; names, types and the function descriptions stay. */
    static JsonArray compact(JsonArray tools) {
        JsonArray out = tools.deepCopy();
        for (JsonElement t : out) {
            JsonObject fn = t.getAsJsonObject().getAsJsonObject("function");
            if (!fn.has("parameters")) continue;
            for (Map.Entry<String, JsonElement> p : fn.getAsJsonObject("parameters").getAsJsonObject("properties").entrySet()) {
                p.getValue().getAsJsonObject().remove("description");
            }
        }
        return out;
    }

    private static JsonArray core() {
        JsonArray tools = new JsonArray();
        tools.add(fn("say", "Say something in public chat. Players nearby hear it.",
                props("text", "string", "What to say. One short chat message."), "text"));
        JsonObject xyz = new JsonObject();
        xyz.add("x", prop("integer", "Block x (east is positive)."));
        xyz.add("y", prop("integer", "Block y (height)."));
        xyz.add("z", prop("integer", "Block z (south is positive)."));
        tools.add(fn("goTo", "Walk to block coordinates. Finishes within 1.5 blocks of them.", xyz, "x", "y", "z"));
        tools.add(fn("goToPlayer", "Walk to a player until you are within 3 blocks of them.",
                props("name", "string", "The player's exact name."), "name"));
        JsonObject follow = props("name", "string", "The player's exact name.");
        follow.add("seconds", prop("integer", "How long to follow, 1 to 300. Default 60."));
        tools.add(fn("follow", "Follow a player for a while, staying close to them.", follow, "name"));
        JsonObject explore = props("direction", "string", "N, NE, E, SE, S, SW, W or NW.");
        explore.add("distance", prop("integer", "Blocks to walk, 1 to 128. Default 64."));
        tools.add(fn("explore", "Walk in a compass direction to see new terrain. On the way you may swim and drop up to 3 blocks.", explore, "direction"));
        JsonObject mine = props("block", "string", "Block id such as stone, iron_ore or oak_log; 'log' means any tree.");
        mine.add("count", prop("integer", "How many more to collect, 1 to 64. Default 1."));
        tools.add(fn("mine", "Mine `count` more of a block, moving between blocks or trees as needed; use 'log' for any tree. Uses your best tool and picks the drops up.", mine, "block"));
        tools.add(fn("collect", "Pick up dropped items of one kind lying nearby.",
                props("item", "string", "Item id, e.g. oak_log."), "item"));
        JsonObject craft = props("item", "string", "Item id, e.g. wooden_pickaxe.");
        craft.add("count", prop("integer", "How many to have made, 1 to 64. Default 1."));
        tools.add(fn("craft", "Craft `count` of an item; makes needed intermediates (planks, sticks, crafting table) from what you carry, and places a crafting table if one is needed.", craft, "item"));
        JsonObject smelt = props("item", "string", "What to smelt, e.g. raw_iron or raw_beef.");
        smelt.add("count", prop("integer", "How many to smelt, 1 to 64. Default 1."));
        tools.add(fn("smelt", "Smelt `count` of an item in a furnace with fuel from your inventory; places a furnace if you carry one or can craft one.", smelt, "item"));
        JsonObject place = props("block", "string", "Block id you carry, e.g. torch.");
        place.add("x", prop("integer", "Block x."));
        place.add("y", prop("integer", "Block y."));
        place.add("z", prop("integer", "Block z."));
        tools.add(fn("place", "Place a block you carry at coordinates within reach.", place, "block", "x", "y", "z"));
        tools.add(fn("eat", "Eat the best food you carry.", null));
        tools.add(fn("equip", "Put on armor or hold a tool or weapon from your inventory.",
                props("item", "string", "Item id, e.g. iron_chestplate or stone_sword."), "item"));
        tools.add(fn("attack", "Fight the nearest mob of a type or a player until it is dead, using your best weapon.",
                props("target", "string", "A mob type such as zombie, or a player's name."), "target"));
        tools.add(fn("stop", "Stop what you are doing and stand still.", null));
        return tools;
    }

    public static String guide() {
        return guide(ActionFactory.available(), ToolModules.guideLines());
    }

    /** The guide for the tools {@link #tools(Mode, SnapshotData, BotContext)} sends: their names and their modules' lines. */
    public static String guide(Mode mode, SnapshotData snap, BotContext ctx) {
        if (mode == Mode.FULL) return guide();
        List<String> names = new ArrayList<>();
        for (JsonElement t : tools(Mode.GATED, snap, ctx)) names.add(t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        return guide(String.join(", ", names), ToolModules.guideLines(snap, ctx));
    }

    private static String guide(String actions, List<String> lines) {
        StringBuilder guide = new StringBuilder("Actions: ").append(actions).append("""
                . Each one ends as done or failed and you will be told which.
                Do more with less: one turn can chain up to 6 actions, e.g. mine log 6 -> craft wooden_pickaxe -> mine stone 3 -> craft stone_pickaxe.
                If you cannot call functions, reply with only a JSON array instead, for example:
                [{"action": "say", "args": {"text": "hello"}}, {"action": "goToPlayer", "args": {"name": "Steve"}}]""");
        for (String line : lines) guide.append('\n').append(line);
        return guide.toString();
    }
}
