package com.aiciv.storage;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.ActionFactory.Result;
import com.aiciv.actions.Args;
import com.aiciv.actions.Attachments;
import com.aiciv.actions.SayAction;
import com.aiciv.bot.AiPlayer;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.aiciv.perception.SnapshotSection;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Chests, giving and dropping items, renaming at an anvil, name tags and signs. */
public final class StorageModule implements ToolModule {
    static final String CHEST_ITEM = "chest_item:";
    private static final int MAX_NAME = 50;

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public List<JsonObject> tools() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(Tools.fn("place_chest", "Place a chest from your inventory on the ground next to you.", null));
        tools.add(Tools.fn("look_in_chest", "Walk to a chest or barrel (the nearest within 16 blocks unless x, y, z are given), open it and learn what is inside.",
                withPos(new JsonObject())));
        JsonObject deposit = withPos(Tools.props("items", "string",
                "all, all_except_tools (keeps tools, weapons, armor and food) or a list like \"cobblestone x20, oak_log\""));
        tools.add(Tools.fn("deposit", "Walk to a chest and put items from your inventory into it.", deposit, "items"));
        JsonObject withdraw = Tools.props("item", "string", "item to take, e.g. cobblestone");
        withdraw.add("count", Tools.prop("integer", "how many, 1 to 64 (default 1)"));
        tools.add(Tools.fn("withdraw", "Walk to a chest and take items out of it, as many as fit in your inventory.", withPos(withdraw), "item"));
        JsonObject give = Tools.props("name", "string", "the player to give to");
        give.add("item", Tools.prop("string", "item to give, e.g. bread"));
        give.add("count", Tools.prop("integer", "how many, 1 to 64 (default 1)"));
        tools.add(Tools.fn("give", "Walk up to a player and throw items to them. They must be online and in the same world.", give, "name", "item"));
        JsonObject drop = Tools.props("item", "string", "item to throw, e.g. cobblestone");
        drop.add("count", Tools.prop("integer", "how many, 1 to 64 (default 1)"));
        tools.add(Tools.fn("drop", "Throw items from your inventory onto the ground in front of you.", drop, "item"));
        JsonObject rename = Tools.props("item", "string", "item in your inventory to rename, e.g. name_tag");
        rename.add("name", Tools.prop("string", "the new name, 1 to 50 characters"));
        tools.add(Tools.fn("rename", "Rename an item at an anvil. Costs experience levels (usually 1).", rename, "item", "name"));
        JsonObject nameMob = Tools.props("target", "string", "the kind of mob to name, e.g. cow");
        nameMob.add("name", Tools.prop("string", "the name; a name_tag with exactly this name is used, or a plain one is renamed at an anvil first"));
        tools.add(Tools.fn("name_mob", "Give a nearby animal or mob a name with a name tag.", nameMob, "target", "name"));
        tools.add(Tools.fn("sign", "Place a sign you carry and write text on it (up to 4 lines of 15 characters, 60 in all).",
                withPos(Tools.props("text", "string", "what the sign says")), "text"));
        return tools;
    }

    private static JsonObject withPos(JsonObject props) {
        props.add("x", Tools.prop("integer", "block x of the target; give x, y and z together or none"));
        props.add("y", Tools.prop("integer", "block y"));
        props.add("z", Tools.prop("integer", "block z"));
        return props;
    }

    @Override
    public Result create(String key, JsonObject a) {
        switch (key) {
            case "placechest":
                return new Result.Ok(new PlaceChestAction());
            case "lookinchest": {
                String problem = Args.posProblem("look_in_chest", a);
                if (problem != null) return invalid(problem);
                return new Result.Ok(new LookInChestAction(Args.optionalPos(a).orElse(null)));
            }
            case "deposit": {
                String items = Args.str(a, "items");
                if (items == null || items.isBlank()) return invalid(DepositRule.USAGE);
                DepositRule rule;
                try {
                    rule = DepositRule.parse(items);
                } catch (IllegalArgumentException e) {
                    return invalid(e.getMessage());
                }
                String problem = Args.posProblem("deposit", a);
                if (problem != null) return invalid(problem);
                return new Result.Ok(new DepositAction(rule, Args.optionalPos(a).orElse(null)));
            }
            case "withdraw": {
                String item = Args.str(a, "item");
                if (item == null || item.isBlank()) return invalid("withdraw needs an \"item\" name, e.g. cobblestone");
                String problem = Args.posProblem("withdraw", a);
                if (problem != null) return invalid(problem);
                return new Result.Ok(new WithdrawAction(item.trim(), Args.intIn(a, "count", 1, 1, 64), Args.optionalPos(a).orElse(null)));
            }
            case "give": {
                String name = Args.str(a, "name");
                if (name == null || name.isBlank()) return invalid("give needs a player \"name\"");
                String item = Args.str(a, "item");
                if (item == null || item.isBlank()) return invalid("give needs an \"item\" name, e.g. bread");
                return new Result.Ok(new GiveAction(name.trim(), item.trim(), Args.intIn(a, "count", 1, 1, 64)));
            }
            case "drop": {
                String item = Args.str(a, "item");
                if (item == null || item.isBlank()) return invalid("drop needs an \"item\" name, e.g. cobblestone");
                return new Result.Ok(new DropAction(item.trim(), Args.intIn(a, "count", 1, 1, 64)));
            }
            case "rename": {
                String item = Args.str(a, "item");
                if (item == null || item.isBlank()) return invalid("rename needs an \"item\" to rename, e.g. name_tag");
                String name = itemName(Args.str(a, "name"));
                if (name == null) return invalid("rename needs a \"name\" of 1 to " + MAX_NAME + " characters");
                return new Result.Ok(new RenameAction(item.trim(), name));
            }
            case "namemob": {
                String target = Args.str(a, "target");
                if (target == null || target.isBlank()) return invalid("name_mob needs a \"target\" mob type, e.g. cow");
                String name = itemName(Args.str(a, "name"));
                if (name == null) return invalid("name_mob needs a \"name\" of 1 to " + MAX_NAME + " characters");
                return new Result.Ok(new NameMobAction(target.trim(), name));
            }
            case "sign": {
                String raw = Args.str(a, "text");
                String text = raw == null ? "" : SayAction.sanitize(raw);
                if (text.isEmpty()) return invalid("sign needs non-empty \"text\"");
                if (text.length() > SignLines.MAX_LENGTH) return invalid("sign \"text\" must be at most " + SignLines.MAX_LENGTH + " characters");
                String problem = Args.posProblem("sign", a);
                if (problem != null) return invalid(problem);
                return new Result.Ok(new SignAction(text, Args.optionalPos(a).orElse(null)));
            }
            default:
                return null;
        }
    }

    /** The cleaned name, or null when it is empty or longer than an anvil accepts. */
    private static String itemName(String raw) {
        if (raw == null) return null;
        String name = SayAction.sanitize(raw);
        return name.isEmpty() || name.length() > MAX_NAME ? null : name;
    }

    private static Result invalid(String reason) {
        return new Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Chests: place_chest, look_in_chest, deposit \"all_except_tools\", withdraw item count. Share with give (walks over and throws).");
    }

    // ---------------------------------------------------------------- menu

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<MenuOption> out = new ArrayList<>();
        boolean chestNear = snap.blocks().stream().anyMatch(StorageModule::nearChest);
        if (chestNear) {
            out.add(option("look_in_chest", "walk to the nearest chest and see what is inside", call("look_in_chest")));
            if (ctx.inventory().size() >= 6) {
                out.add(option("deposit_all_except_tools", "put everything except tools, weapons, armor and food into the nearest chest",
                        call("deposit", "items", "all_except_tools")));
            }
        } else if (ctx.inventory().getOrDefault("chest", 0) > 0) {
            out.add(option("place_chest", "place your chest on the ground next to you", call("place_chest")));
        }
        int offered = 0;
        for (SnapshotSection section : snap.sections()) {
            for (String tag : section.tags()) {
                if (!tag.startsWith(CHEST_ITEM) || offered >= 3) continue;
                String id = tag.substring(CHEST_ITEM.length());
                out.add(option("withdraw_" + id, "take 16 " + id + " out of the chest you remember", call("withdraw", "item", id, "count", 16)));
                offered++;
            }
        }
        return out;
    }

    private static boolean nearChest(Sighting s) {
        return (s.label().equals("chest") || s.label().equals("barrel")) && s.distance() <= ContainerTarget.RADIUS;
    }

    private static MenuOption option(String id, String description, ToolCall call) {
        if (id.length() > 40) id = id.substring(0, 40);
        return new MenuOption(id, description, List.of(call));
    }

    private static ToolCall call(String name, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return ToolCall.of(name, args);
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(new Sighting("chest", 1, 5, "E")),
                List.of(new SnapshotSection("Chests", List.of("chest at 5 64 0 (5m E): cobblestone x40"), null, List.of(CHEST_ITEM + "cobblestone"))));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        for (String id : List.of("dirt", "cobblestone", "oak_log", "sand", "gravel", "stone_pickaxe", "chest")) inventory.put(id, 3);
        return Samples.context(inventory);
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        ChestMemory chests = memory.find(ChestMemory.class).orElse(null);
        if (chests == null) return List.of();
        ServerLevel level = bot.level();
        for (Pos p : chests.positions()) { // a chest that has been broken is no longer worth remembering
            BlockPos at = new BlockPos(p.x(), p.y(), p.z());
            if (level.isLoaded(at) && !ContainerTarget.isContainer(BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath())) {
                chests.forget(p);
            }
        }
        BlockPos feet = bot.blockPosition();
        Pos here = new Pos(feet.getX(), feet.getY(), feet.getZ());
        List<String> lines = chests.describe(here, 4);
        if (lines.isEmpty()) return List.of();
        List<String> tags = new ArrayList<>();
        for (String id : chests.nearestKinds(here)) tags.add(CHEST_ITEM + id);
        return List.of(new SnapshotSection("Chests", lines, null, tags));
    }
}
