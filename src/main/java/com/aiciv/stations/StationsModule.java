package com.aiciv.stations;

import com.aiciv.actions.ActionFactory;
import com.aiciv.actions.Args;
import com.aiciv.brain.MenuOption;
import com.aiciv.brain.OptionMenu;
import com.aiciv.llm.ToolCall;
import com.aiciv.modules.Samples;
import com.aiciv.modules.ToolModule;
import com.aiciv.modules.Tools;
import com.aiciv.perception.Sighting;
import com.aiciv.perception.SnapshotData;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/** Smithing, stonecutting, grindstones, looms, cartography and books. */
public final class StationsModule implements ToolModule {
    static final int SIGHT_RADIUS = 16;
    static final int MAX_PAGES = 10;
    private static final List<String> DIAMOND_PIECES = List.of("sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots");

    @Override
    public String name() {
        return "stations";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject upgrade = Tools.props("item", "string", "diamond tool or armor you hold, e.g. diamond_sword");
        JsonObject trim = Tools.props("item", "string", "armor piece you hold, e.g. iron_chestplate");
        trim.add("template", Tools.prop("string", "trim pattern, e.g. coast, sentry or wild"));
        trim.add("material", Tools.prop("string", "iron, gold, copper, diamond, emerald, lapis, quartz, redstone, amethyst, netherite or resin"));
        JsonObject cut = Tools.props("input", "string", "block you hold, e.g. stone");
        cut.add("output", Tools.prop("string", "what to make, e.g. stone_bricks or oak_slab"));
        cut.add("count", Tools.prop("integer", "how many to make, 1 to 64; default 1"));
        JsonObject grind = Tools.props("item", "string", "the enchanted item you hold, e.g. iron_pickaxe");
        JsonObject repair = Tools.props("item", "string", "a tool or armor you hold two worn copies of, e.g. iron_pickaxe");
        JsonObject loom = Tools.props("pattern", "string", "e.g. bottom stripe, cross, border, creeper, flower, skull or globe");
        loom.add("color", Tools.prop("string", "dye colour for the pattern, e.g. red"));
        loom.add("banner", Tools.prop("string", "colour of the banner to use; default any banner you hold"));
        JsonObject map = Tools.props("action", "string", "zoom (needs paper), copy (needs a map) or lock (needs glass_pane)");
        JsonObject book = Tools.props("text", "string", "what to write; up to 10 pages of 1024 characters");
        book.add("title", Tools.prop("string", "if given, signs the book with this title (1 to 32 characters)"));
        return List.of(
                Tools.fn("upgrade_netherite", "Upgrade a diamond tool or armor piece to netherite at a smithing table. Needs a netherite_upgrade_smithing_template and a netherite_ingot; enchantments stay.", upgrade, "item"),
                Tools.fn("trim_armor", "Put an armor trim on armor at a smithing table. Needs the trim's smithing template and a material item such as an iron_ingot.", trim, "item", "template", "material"),
                Tools.fn("stonecut", "Cut a block into another shape at a stonecutter (stone to stone_bricks, planks to slabs or stairs...).", cut, "input", "output"),
                Tools.fn("grindstone", "Remove the enchantments from one enchanted item at a grindstone. You get some experience back.", grind, "item"),
                Tools.fn("repair", "Join two worn copies of the same tool or armor into one repaired item at a grindstone.", repair, "item"),
                Tools.fn("loom", "Add a pattern layer to a banner you hold at a loom, using a dye. Special patterns also need their banner pattern item.", loom, "pattern", "color"),
                Tools.fn("cartography", "Zoom out, copy or lock a filled map you hold at a cartography table.", map, "action"),
                Tools.fn("write_book", "Write text into a book and quill you hold. With a title the book is signed and can no longer be changed.", book, "text"));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "upgradenetherite": {
                String item = Args.str(args, "item");
                if (blank(item)) return invalid("upgrade_netherite needs an \"item\", e.g. diamond_sword");
                if (StationNames.netheriteResult(item).isEmpty()) {
                    return invalid("upgrade_netherite works on diamond tools and armor (diamond_sword, diamond_chestplate...), not " + item.trim());
                }
                return ok(new UpgradeNetheriteAction(item));
            }
            case "trimarmor": {
                String item = Args.str(args, "item"), template = Args.str(args, "template"), material = Args.str(args, "material");
                if (blank(item)) return invalid("trim_armor needs an \"item\", e.g. iron_chestplate");
                if (blank(template)) return invalid("trim_armor needs a \"template\", one of " + String.join(", ", StationNames.trimNames()));
                if (blank(material)) return invalid("trim_armor needs a \"material\", e.g. iron or gold");
                var trimItem = StationNames.trimTemplate(template);
                if (trimItem.isEmpty()) return invalid("no trim called " + template.trim() + "; trims are " + String.join(", ", StationNames.trimNames()));
                var materialItem = StationNames.trimMaterial(material);
                if (materialItem.isEmpty()) return invalid("no trim material called " + material.trim() + "; use one of " + String.join(", ", StationNames.TRIM_MATERIALS));
                return ok(new TrimArmorAction(item, trimItem.get(), materialItem.get()));
            }
            case "stonecut": {
                String input = Args.str(args, "input"), output = Args.str(args, "output");
                if (blank(input)) return invalid("stonecut needs an \"input\" block you hold, e.g. stone");
                if (blank(output)) return invalid("stonecut needs an \"output\", e.g. stone_bricks");
                return ok(new StonecutAction(input, output, Args.intIn(args, "count", 1, 1, 64)));
            }
            case "grindstone": {
                String item = Args.str(args, "item");
                if (blank(item)) return invalid("grindstone needs an \"item\" you hold with enchantments, e.g. iron_pickaxe");
                return ok(new GrindstoneAction(item, false));
            }
            case "repair": {
                String item = Args.str(args, "item");
                if (blank(item)) return invalid("repair needs an \"item\" you hold two worn copies of, e.g. iron_pickaxe");
                return ok(new GrindstoneAction(item, true));
            }
            case "loom": {
                String pattern = Args.str(args, "pattern"), color = Args.str(args, "color"), banner = Args.str(args, "banner");
                if (blank(pattern)) return invalid("loom needs a \"pattern\", e.g. bottom stripe, cross or creeper");
                if (blank(color)) return invalid("loom needs a \"color\" for the dye, e.g. red");
                if (DyeColor.byName(StationNames.normalise(color), null) == null) return invalid("no dye colour called " + color.trim() + "; colours are " + colours());
                if (!blank(banner) && DyeColor.byName(StationNames.normalise(banner), null) == null) {
                    return invalid("no banner colour called " + banner.trim() + "; colours are " + colours());
                }
                return ok(new LoomAction(pattern, color, banner));
            }
            case "cartography": {
                String action = Args.str(args, "action");
                if (blank(action)) return invalid("cartography needs an \"action\": " + CartographyAction.ACTIONS);
                String a = action.trim().toLowerCase(java.util.Locale.ROOT);
                if (!List.of("zoom", "copy", "lock").contains(a)) return invalid("cartography action must be " + CartographyAction.ACTIONS + ", not " + action.trim());
                return ok(new CartographyAction(a));
            }
            case "writebook": {
                String text = Args.str(args, "text"), title = Args.str(args, "title");
                if (text == null || text.isEmpty()) return invalid("write_book needs the \"text\" to write");
                int limit = WritableBookContent.PAGE_EDIT_LENGTH * MAX_PAGES;
                if (text.length() > limit) return invalid("write_book text is " + text.length() + " characters; a book holds at most " + limit + " (" + MAX_PAGES + " pages)");
                if (!blank(title) && title.trim().length() > WrittenBookContent.TITLE_MAX_LENGTH) {
                    return invalid("write_book \"title\" is " + title.trim().length() + " characters; at most " + WrittenBookContent.TITLE_MAX_LENGTH);
                }
                return ok(new WriteBookAction(StationNames.pages(text, WritableBookContent.PAGE_EDIT_LENGTH), title));
            }
            default:
                return null;
        }
    }

    private static String colours() {
        List<String> names = new ArrayList<>();
        for (DyeColor c : DyeColor.values()) names.add(c.getName());
        return String.join(", ", names);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    private static ActionFactory.Result ok(com.aiciv.actions.Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Stations: upgrade_netherite, trim_armor at a smithing_table; stonecut; grindstone removes enchantments, repair joins two worn tools; "
                + "loom patterns banners; cartography zooms/copies/locks maps; write_book (title signs it).");
    }

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        List<MenuOption> out = new ArrayList<>();
        Map<String, Integer> inv = ctx.inventory();
        if (sees(snap, "smithing_table") && inv.containsKey(UpgradeNetheriteAction.TEMPLATE) && inv.containsKey("netherite_ingot")) {
            for (String piece : DIAMOND_PIECES) {
                String item = "diamond_" + piece;
                if (!inv.containsKey(item)) continue;
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                out.add(new MenuOption("upgrade_" + item, "upgrade your " + item + " to netherite at the smithing table", List.of(ToolCall.of("upgrade_netherite", args))));
                break;
            }
        }
        int stone = inv.getOrDefault("stone", 0);
        if (sees(snap, "stonecutter") && stone >= 4) {
            JsonObject args = new JsonObject();
            args.addProperty("input", "stone");
            args.addProperty("output", "stone_bricks");
            args.addProperty("count", Math.min(stone, 16));
            out.add(new MenuOption("stonecut_stone_bricks", "cut your stone into stone_bricks at the stonecutter", List.of(ToolCall.of("stonecut", args))));
        }
        return out;
    }

    private static boolean sees(SnapshotData snap, String label) {
        for (Sighting s : snap.blocks()) if (s.distance() <= SIGHT_RADIUS && s.label().equals(label)) return true;
        return false;
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(new Sighting("smithing_table", 2, 3, "north")), List.of());
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("diamond_sword", 1, UpgradeNetheriteAction.TEMPLATE, 1, "netherite_ingot", 1));
    }
}
