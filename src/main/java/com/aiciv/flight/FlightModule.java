package com.aiciv.flight;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.gear.GearReport;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.Situation;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.aiciv.world.InventoryOps;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Elytra flight and ender pearls. */
public final class FlightModule implements ToolModule {
    static final String HAS_ELYTRA = "has_elytra", ELYTRA_WORN = "elytra_worn", ROCKETS = "rockets", PEARLS = "pearls";
    /** How far a menu flight goes. */
    static final int MENU_FLIGHT = 256;

    @Override
    public String name() {
        return "flight";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject flyTo = Tools.props("x", "integer", "target x");
        flyTo.add("z", Tools.prop("integer", "target z"));
        JsonObject pearl = Tools.props("x", "integer", "x of the block to land on");
        pearl.add("y", Tools.prop("integer", "y of the block to land on (you land on top)"));
        pearl.add("z", Tools.prop("integer", "z of the block to land on"));
        return List.of(
                Tools.fn("fly_to", "Glide with your elytra to x z (16 to 1024 blocks away), taking off first and using firework rockets "
                        + "to keep height. Lands within 16 blocks of it.", flyTo, "x", "z"),
                Tools.fn("launch_elytra", "Wear the elytra you carry, jump and open the wings. Jump from 3+ blocks up, or carry "
                        + "firework rockets.", null),
                Tools.fn("boost", "Fire one firework rocket while gliding, for a burst of speed.", null),
                Tools.fn("throw_pearl", "Throw an ender pearl to teleport onto a block you can see 3 to 40 blocks away. It costs 5 health.",
                        pearl, "x", "y", "z"));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "flyto": {
                String missing = missing(args, "fly_to", "x", "z");
                if (missing != null) return new ActionFactory.Result.Invalid(missing);
                return new ActionFactory.Result.Ok(new FlyToAction(Args.integer(args, "x"), Args.integer(args, "z")));
            }
            case "launchelytra":
                return new ActionFactory.Result.Ok(new LaunchElytraAction());
            case "boost":
                return new ActionFactory.Result.Ok(new BoostAction());
            case "throwpearl": {
                String missing = missing(args, "throw_pearl", "x", "y", "z");
                if (missing != null) return new ActionFactory.Result.Invalid(missing);
                int y = Args.integer(args, "y");
                if (y < -64 || y > 320) return new ActionFactory.Result.Invalid("throw_pearl \"y\" must be between -64 and 320");
                return new ActionFactory.Result.Ok(new ThrowPearlAction(Args.integer(args, "x"), y, Args.integer(args, "z")));
            }
            default:
                return null;
        }
    }

    /** The first of {@code names} that is not a whole number in {@code args}, as an Invalid reason; null when all are. */
    private static String missing(JsonObject args, String tool, String... names) {
        for (String n : names) {
            if (Args.integer(args, n) == null) return tool + " needs a whole number \"" + n + "\"";
        }
        return null;
    }

    /** With an elytra worn or carried, or ender pearls to throw. */
    @Override
    public boolean relevant(SnapshotData snap, BotContext ctx) {
        return Situation.carries(snap, id -> id.equals("elytra") || id.equals("ender_pearl"));
    }

    @Override
    public List<String> guideLines() {
        return List.of("Flight: fly_to x z with an elytra (rockets help); throw_pearl x y z lands you on a block you can see, for 5 health.");
    }

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        List<String> items = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        ItemStack worn = bot.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack elytra = worn.is(Items.ELYTRA) ? worn : ItemStack.EMPTY;
        boolean isWorn = !elytra.isEmpty();
        if (elytra.isEmpty()) {
            for (ItemStack s : bot.getInventory().getNonEquipmentItems()) {
                if (s.is(Items.ELYTRA)) {
                    elytra = s;
                    break;
                }
            }
        }
        if (!elytra.isEmpty()) {
            items.add("elytra " + GearReport.percentLeft(elytra.getDamageValue(), elytra.getMaxDamage()) + "%" + (isWorn ? " (worn)" : ""));
            tags.add(HAS_ELYTRA);
            if (isWorn && elytra.getMaxDamage() - elytra.getDamageValue() > LaunchElytraAction.MIN_DURABILITY) tags.add(ELYTRA_WORN);
        }
        int rockets = InventoryOps.count(bot, s -> s.is(Items.FIREWORK_ROCKET));
        if (rockets > 0) {
            items.add("firework_rocket x" + rockets);
            tags.add(ROCKETS);
        }
        int pearls = InventoryOps.count(bot, s -> s.is(Items.ENDER_PEARL));
        if (pearls > 0) {
            items.add("ender_pearl x" + pearls);
            tags.add(PEARLS);
        }
        if (items.isEmpty()) return List.of();
        return List.of(new SnapshotSection("Flight", items, null, tags));
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        if (!tags.contains(ELYTRA_WORN) || !tags.contains(ROCKETS)) return List.of();
        List<MenuOption> out = new ArrayList<>();
        out.add(new MenuOption("launch_elytra", "take off with your elytra", List.of(ToolCall.of("launch_elytra", new JsonObject()))));
        out.add(flight("north", snap.x(), snap.z() - MENU_FLIGHT));
        out.add(flight("east", snap.x() + MENU_FLIGHT, snap.z()));
        out.add(flight("south", snap.x(), snap.z() + MENU_FLIGHT));
        out.add(flight("west", snap.x() - MENU_FLIGHT, snap.z()));
        return out;
    }

    private static MenuOption flight(String direction, int x, int z) {
        JsonObject args = new JsonObject();
        args.addProperty("x", x);
        args.addProperty("z", z);
        return new MenuOption("fly_" + direction, "fly " + MENU_FLIGHT + " blocks " + direction + " with your elytra and rockets",
                List.of(ToolCall.of("fly_to", args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(), List.of(new SnapshotSection("Flight",
                List.of("elytra 87% (worn)", "firework_rocket x8"), null, List.of(HAS_ELYTRA, ELYTRA_WORN, ROCKETS))));
    }
}
