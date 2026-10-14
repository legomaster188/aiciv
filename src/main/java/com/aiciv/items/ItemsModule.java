package com.aiciv.items;

import com.aiciv.actions.Action;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;

/** Buckets, bone meal, fire, potions, throwables, maps and compasses. */
public final class ItemsModule implements ToolModule {
    static final String HAS_COMPASS = "has_compass";
    static final String HAS_MAP = "has_map";
    static final String BUCKET_EMPTY = "bucket_empty";
    static final String BUCKET_WATER = "bucket_water";
    static final String POTION_TAG = "potion:";
    private static final int WATER_RADIUS = 8;
    private static final int MAX_LINES = 6;

    @Override
    public String name() {
        return "items";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject fill = Tools.props("fluid", "string", "water or lava");
        addPos(fill, "source block ");
        JsonObject empty = new JsonObject();
        addPos(empty, "cell ");
        JsonObject bone = new JsonObject();
        addPos(bone, "plant ");
        bone.add("count", Tools.prop("integer", "uses, 1 to 8 (default 3)"));
        JsonObject ignite = new JsonObject();
        addPos(ignite, "block ");
        JsonObject carve = new JsonObject();
        addPos(carve, "pumpkin ");
        JsonObject drink = Tools.props("item", "string", "swiftness, healing, strength, ..., water, milk or honey");
        JsonObject toss = Tools.props("item", "string", "snowball, egg, splash healing, lingering_potion, experience_bottle");
        addPos(toss, "aim block ");
        toss.add("target", Tools.prop("string", "mob type or player to throw at, instead of x y z"));
        JsonObject link = new JsonObject();
        addPos(link, "lodestone ");
        return List.of(
                Tools.fn("fill_bucket", "Fill an empty bucket from a water or lava source in sight (within 8 blocks, or at x y z).", fill, "fluid"),
                Tools.fn("empty_bucket", "Pour your water or lava bucket into the cell at x y z.", empty, "x", "y", "z"),
                Tools.fn("bone_meal", "Use bone meal on a growing crop, sapling or grass you can see (or at x y z) until it is ripe or grown.", bone),
                Tools.fn("ignite", "Light TNT, a campfire or a candle at x y z, or set fire on top of a block. Needs flint_and_steel or a fire_charge.", ignite, "x", "y", "z"),
                Tools.fn("carve_pumpkin", "Carve a pumpkin in sight with shears and pick up the seeds.", carve),
                Tools.fn("drink", "Drink a potion you hold by name, or milk or honey.", drink, "item"),
                Tools.fn("throw", "Throw a snowball, egg, splash or lingering potion or experience bottle at x y z or at a mob or player.", toss, "item"),
                Tools.fn("make_map", "Use an empty map to map the area around you.", null),
                Tools.fn("link_compass", "Point a compass at a lodestone in sight (or at x y z) by using it on the lodestone.", link));
    }

    private static void addPos(JsonObject props, String what) {
        props.add("x", Tools.prop("integer", what + "x"));
        props.add("y", Tools.prop("integer", what + "y"));
        props.add("z", Tools.prop("integer", what + "z"));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "fillbucket": {
                String fluid = Args.str(args, "fluid");
                if (fluid == null || fluid.isBlank()) return invalid("fill_bucket needs a \"fluid\": water or lava");
                fluid = fluid.trim().toLowerCase(java.util.Locale.ROOT);
                if (!fluid.equals("water") && !fluid.equals("lava")) return invalid("fluid must be water or lava");
                String problem = Args.posProblem("fill_bucket", args);
                if (problem != null) return invalid(problem);
                return ok(new FillBucketAction(fluid, Args.optionalPos(args).orElse(null)));
            }
            case "emptybucket": {
                BlockPos pos = requirePos("empty_bucket", args);
                if (pos == null) return invalid(posReason("empty_bucket", args));
                return ok(new EmptyBucketAction(pos.getX(), pos.getY(), pos.getZ()));
            }
            case "bonemeal": {
                String problem = Args.posProblem("bone_meal", args);
                if (problem != null) return invalid(problem);
                return ok(new BoneMealAction(Args.optionalPos(args).orElse(null), Args.intIn(args, "count", 3, 1, 8)));
            }
            case "ignite": {
                BlockPos pos = requirePos("ignite", args);
                if (pos == null) return invalid(posReason("ignite", args));
                return ok(new IgniteAction(pos.getX(), pos.getY(), pos.getZ()));
            }
            case "carvepumpkin": {
                String problem = Args.posProblem("carve_pumpkin", args);
                if (problem != null) return invalid(problem);
                return ok(new CarvePumpkinAction(Args.optionalPos(args).orElse(null)));
            }
            case "drink": {
                String item = Args.str(args, "item");
                if (item == null || item.isBlank()) return invalid("drink needs an \"item\": a potion name, milk or honey");
                return ok(new DrinkAction(item.trim()));
            }
            case "throw": {
                String item = Args.str(args, "item");
                if (item == null || item.isBlank()) return invalid("throw needs an \"item\": snowball, egg, splash healing ...");
                String problem = Args.posProblem("throw", args);
                if (problem != null) return invalid(problem);
                Optional<BlockPos> at = Args.optionalPos(args);
                String target = Args.str(args, "target");
                boolean hasTarget = target != null && !target.isBlank();
                if (at.isPresent() && hasTarget) return invalid("use either x y z or \"target\", not both");
                if (at.isEmpty() && !hasTarget) return invalid("throw needs x y z or a \"target\"");
                return ok(new ThrowAction(item.trim(), at.orElse(null), hasTarget ? target.trim() : null));
            }
            case "makemap":
                return ok(new MakeMapAction());
            case "linkcompass": {
                String problem = Args.posProblem("link_compass", args);
                if (problem != null) return invalid(problem);
                return ok(new LinkCompassAction(Args.optionalPos(args).orElse(null)));
            }
            default:
                return null;
        }
    }

    /** x, y and z when all three are present and valid; else null (see {@link #posReason}). */
    private static BlockPos requirePos(String action, JsonObject args) {
        for (String axis : new String[] {"x", "y", "z"}) if (Args.integer(args, axis) == null) return null;
        if (Args.posProblem(action, args) != null) return null;
        return Args.optionalPos(args).orElse(null);
    }

    private static String posReason(String action, JsonObject args) {
        for (String axis : new String[] {"x", "y", "z"}) {
            if (Args.integer(args, axis) == null) return action + " needs a whole number \"" + axis + "\"";
        }
        return Args.posProblem(action, args);
    }

    private static ActionFactory.Result ok(Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Items: fill_bucket/empty_bucket, bone_meal crops, ignite with flint_and_steel, drink potions or milk, "
                + "throw snowballs, eggs and splash potions, make_map, link_compass to a lodestone.");
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        Map<String, Integer> inv = ctx.inventory();
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        List<MenuOption> out = new ArrayList<>();
        boolean emptyBucket = inv.getOrDefault("bucket", 0) > 0 || tags.contains(BUCKET_EMPTY);
        boolean water = snap.blocks().stream().anyMatch(b -> b.label().equals("water") && b.distance() <= WATER_RADIUS);
        if (emptyBucket && water) {
            out.add(option("fill_water_bucket", "fill your bucket with water from the pool in sight", "fill_bucket", "fluid", "water"));
        }
        for (String tag : tags) {
            if (!tag.startsWith(POTION_TAG)) continue;
            String potion = tag.substring(POTION_TAG.length());
            if (potion.equals("water") || potion.equals("awkward") || !OptionMenu.ID.matcher("drink_" + potion).matches()) continue;
            out.add(option("drink_" + potion, "drink your potion of " + potion.replace('_', ' '), "drink", "item", potion));
            break;
        }
        boolean growing = snap.sections().stream().anyMatch(s -> s.label().equals("Crops") && s.items().stream().anyMatch(i -> i.contains("growing")));
        if (inv.getOrDefault("bone_meal", 0) > 0 && growing) {
            out.add(option("bone_meal_crops", "use bone meal on the crops that are still growing", "bone_meal", "count", 3));
        }
        if (inv.getOrDefault("map", 0) > 0) out.add(option("make_map", "use your empty map to map the area around you", "make_map"));
        return out;
    }

    private static MenuOption option(String id, String description, String tool, Object... kv) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
            else args.addProperty((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return new MenuOption(id, description, List.of(ToolCall.of(tool, args)));
    }

    @Override
    public SnapshotData sampleSnapshot() {
        return Samples.snapshot(List.of(new Sighting("water", 5, 3, "N")), List.of());
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("bucket", 1));
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        ServerLevel level = bot.level();
        BlockPos here = bot.blockPosition();
        Set<String> lines = new LinkedHashSet<>();
        List<String> tags = new ArrayList<>();
        Set<String> potions = new LinkedHashSet<>();
        boolean spawnSeen = false, lodestoneSeen = false, recoverySeen = false, bucketEmpty = false, bucketWater = false;
        for (ItemStack s : bot.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) continue;
            if (s.is(Items.BUCKET)) bucketEmpty = true;
            else if (s.is(Items.WATER_BUCKET)) bucketWater = true;
            else if (s.is(Items.POTION)) {
                String key = DrinkAction.potionKey(s);
                if (key != null) potions.add(Potions.base(key));
            } else if (s.is(Items.RECOVERY_COMPASS) && !recoverySeen) {
                recoverySeen = true;
                add(lines, bot.getLastDeathLocation().filter(g -> g.dimension().equals(level.dimension())).map(GlobalPos::pos)
                        .map(p -> CompassReading.line("recovery", p.getX() - here.getX(), p.getZ() - here.getZ())).orElse(null));
                tags.add(HAS_COMPASS);
            } else if (s.is(Items.COMPASS)) {
                LodestoneTracker tracker = s.get(DataComponents.LODESTONE_TRACKER);
                if (tracker != null) {
                    if (lodestoneSeen) continue;
                    lodestoneSeen = true;
                    add(lines, tracker.target().filter(g -> g.dimension().equals(level.dimension())).map(GlobalPos::pos)
                            .map(p -> CompassReading.line("lodestone", p.getX() - here.getX(), p.getZ() - here.getZ())).orElse(null));
                } else {
                    if (spawnSeen) continue;
                    spawnSeen = true;
                    LevelData.RespawnData spawn = level.getRespawnData();
                    if (spawn.dimension().equals(level.dimension())) {
                        add(lines, CompassReading.line("spawn", spawn.pos().getX() - here.getX(), spawn.pos().getZ() - here.getZ()));
                    }
                }
                tags.add(HAS_COMPASS);
            } else if (s.is(Items.FILLED_MAP)) {
                add(lines, mapLine(bot, s));
                tags.add(HAS_MAP);
            }
        }
        if (!potions.isEmpty()) lines.add("potions: " + String.join(", ", potions));
        if (bucketEmpty) tags.add(BUCKET_EMPTY);
        if (bucketWater) tags.add(BUCKET_WATER);
        for (String potion : potions) tags.add(POTION_TAG + potion);
        List<String> items = lines.stream().limit(MAX_LINES).toList();
        return List.of(new SnapshotSection("Items", items, null, tags.stream().distinct().toList()));
    }

    private static void add(Set<String> lines, String line) {
        if (line != null) lines.add(line);
    }

    private static String mapLine(AiPlayer bot, ItemStack map) {
        MapId id = map.get(DataComponents.MAP_ID);
        MapItemSavedData data = MapItem.getSavedData(map, bot.level());
        if (id == null || data == null) return null;
        int size = 128 << data.scale;
        String where;
        if (!data.dimension.equals(bot.level().dimension())) {
            where = "it is of another dimension";
        } else {
            boolean inside = Math.abs(bot.getX() - data.centerX) < size / 2.0 && Math.abs(bot.getZ() - data.centerZ) < size / 2.0;
            where = inside ? "you are inside" : "you are outside it";
        }
        return "map #" + id.id() + " around " + data.centerX + " " + data.centerZ + " (" + size + " blocks), " + where;
    }
}
