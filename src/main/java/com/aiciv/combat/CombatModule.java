package com.aiciv.combat;

import com.aiciv.actions.ActionFactory;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The {@code combat} tool: a chain of short exact fighting moves. */
public final class CombatModule implements ToolModule {
    private static final int MAX_MOB_TYPES = 2;

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public List<JsonObject> tools() {
        return List.of(Tools.fn("combat",
                "Fight with exact moves, run in order: attack_once <target>, strafe left|right <ticks \u226420>, back_off <ticks \u226420>, "
                        + "jump_crit <target> (a falling critical hit), shield <ticks \u226440>, bow <target> <charge ticks, 20 = full>, "
                        + "hotbar <slot 1-9>. 20 ticks = 1 second. Use attack for a simple fight to the death.",
                movesProperty(),
                "moves"));
    }

    private static JsonObject movesProperty() {
        JsonObject props = new JsonObject();
        props.add("moves", Tools.stringArray("Up to 12 moves, in order. Each is a string such as \"attack_once zombie\", \"strafe left 10\", "
                + "\"back_off 8\", \"jump_crit zombie\", \"shield 30\", \"bow skeleton 20\" or \"hotbar 2\" (an object like "
                + "{\"move\":\"strafe\",\"dir\":\"left\",\"ticks\":10} also works). A move without a target uses the previous target."));
        return props;
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        if (!key.equals("combat")) return null;
        try {
            return new ActionFactory.Result.Ok(new CombatAction(CombatMoves.parse(args.get("moves"))));
        } catch (IllegalArgumentException e) {
            return new ActionFactory.Result.Invalid(e.getMessage());
        }
    }

    @Override
    public List<String> guideLines() {
        return List.of("Combat: attack fights to the end; combat runs short exact moves such as [\"attack_once zombie\", \"back_off 8\", \"attack_once zombie\"].");
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        String held = snap.holding();
        boolean melee = held.endsWith("_sword") || held.endsWith("_axe");
        boolean bow = held.equals("bow") && ctx.inventory().keySet().stream().anyMatch(i -> i.endsWith("arrow"));
        boolean shield = held.equals("shield") || snap.equipment().contains("shield") || ctx.inventory().containsKey("shield");
        List<MenuOption> out = new ArrayList<>();
        int mobs = 0;
        for (String mob : ctx.hostileMobs()) {
            if (mobs++ >= MAX_MOB_TYPES) break;
            if (melee) {
                out.add(option("kite_" + mob, "hit the " + mob + " and step back between blows", mob, "attack_once", "back_off 8", "attack_once", "back_off 8", "attack_once"));
            }
            if (bow) out.add(option("shoot_" + mob, "shoot the " + mob + " with your bow", mob, "bow", "bow", "bow"));
            if (shield) out.add(option("block_" + mob, "fight the " + mob + " from behind your shield", mob, "shield 30", "attack_once", "shield 30", "attack_once"));
        }
        return out;
    }

    /** Move words are expanded with the mob: "attack_once" -> "attack_once zombie", "bow" -> "bow zombie 20". */
    private static MenuOption option(String id, String description, String mob, String... moves) {
        JsonArray list = new JsonArray();
        for (String m : moves) {
            if (m.equals("attack_once")) list.add("attack_once " + mob);
            else if (m.equals("bow")) list.add("bow " + mob + " 20");
            else list.add(m);
        }
        JsonObject args = new JsonObject();
        args.add("moves", list);
        return new MenuOption(id, description, List.of(ToolCall.of("combat", args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        SnapshotData s = Samples.snapshot(List.of(), List.of());
        return new SnapshotData(s.name(), s.x(), s.y(), s.z(), s.health(), s.maxHealth(), s.food(), s.day(), s.timeOfDay(),
                s.biome(), "iron_sword", s.equipment(), s.inventory(), s.blocks(), s.players(), s.mobs(), s.remembered(),
                s.currentAction(), s.lastResult(), s.xpLevel(), s.sections());
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("iron_sword", 1), List.of("zombie"));
    }
}
