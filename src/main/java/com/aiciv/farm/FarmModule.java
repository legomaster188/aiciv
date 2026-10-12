package com.aiciv.farm;

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
import com.aiciv.perception.Compass;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Farming, breeding and fishing: till, plant, harvest, breed, fish. */
public final class FarmModule implements ToolModule {
    static final String CROP_RIPE = "crop_ripe";
    static final String FARMLAND_FREE = "farmland_free";
    static final String WATER_NEAR = "water_near";
    private static final int CROP_RADIUS = 16;
    private static final int NEAR_RADIUS = 8;
    private static final List<String> RAW_FOODS = List.of("beef", "porkchop", "chicken", "mutton", "cod", "salmon", "potato");

    @Override
    public String name() {
        return "farm";
    }

    @Override
    public List<JsonObject> tools() {
        JsonObject till = Tools.props("count", "integer", "How many blocks of dirt or grass to till, 1 to 32 (default 4)");
        JsonObject plant = Tools.props("seed", "string", "Seed item to plant: wheat_seeds, carrot, potato, beetroot_seeds, pumpkin_seeds, melon_seeds");
        plant.add("count", Tools.prop("integer", "How many to plant, 1 to 32 (default 4)"));
        JsonObject harvest = Tools.props("count", "integer", "How many ripe crops to harvest, 1 to 32 (default 32)");
        JsonObject breed = Tools.props("animal", "string", "Animal type to breed, e.g. cow, sheep, pig, chicken");
        JsonObject fish = Tools.props("catches", "integer", "How many catches before stopping, 1 to 10 (default 3)");
        return List.of(
                Tools.fn("till", "Till dirt or grass near water into farmland with a hoe you carry.", till),
                Tools.fn("plant", "Plant a seed on empty farmland you can see, from your inventory.", plant, "seed"),
                Tools.fn("harvest", "Harvest ripe crops you can see and replant them from the seeds they drop.", harvest),
                Tools.fn("breed", "Feed two grown animals of one type their food so they breed.", breed, "animal"),
                Tools.fn("fish", "Fish at nearby open water with a fishing rod and keep what you catch.", fish));
    }

    @Override
    public ActionFactory.Result create(String key, JsonObject args) {
        switch (key) {
            case "till":
                return ok(new TillAction(Args.intIn(args, "count", 4, 1, 32)));
            case "plant": {
                String seed = Args.str(args, "seed");
                if (seed == null || seed.isBlank()) return invalid("plant needs a \"seed\" to plant, e.g. wheat_seeds, carrot, potato");
                return ok(new PlantAction(seed.trim(), Args.intIn(args, "count", 4, 1, 32)));
            }
            case "harvest":
                return ok(new HarvestAction(Args.intIn(args, "count", 32, 1, 32)));
            case "breed": {
                String animal = Args.str(args, "animal");
                if (animal == null || animal.isBlank()) return invalid("breed needs an \"animal\" type, e.g. cow, pig, sheep");
                return ok(new BreedAction(animal.trim()));
            }
            case "fish":
                return ok(new FishAction(Args.intIn(args, "catches", 3, 1, 10)));
            default:
                return null;
        }
    }

    private static ActionFactory.Result ok(Action action) {
        return new ActionFactory.Result.Ok(action);
    }

    private static ActionFactory.Result invalid(String reason) {
        return new ActionFactory.Result.Invalid(reason);
    }

    @Override
    public List<String> guideLines() {
        return List.of("Farming: till near water (needs a hoe), plant seeds on farmland, harvest replants. breed feeds two adults "
                + "their food. fish needs a rod. Cook with smelt.");
    }

    // ---------------------------------------------------------------- menus

    @Override
    public List<MenuOption> menuOptions(SnapshotData snap, OptionMenu.MenuContext ctx) {
        Map<String, Integer> inv = ctx.inventory();
        List<String> tags = snap.sections().stream().flatMap(s -> s.tags().stream()).toList();
        boolean farmlandFree = tags.contains(FARMLAND_FREE);
        List<MenuOption> out = new ArrayList<>();
        if (tags.contains(CROP_RIPE)) out.add(option("harvest_crops", "harvest the ripe crops in sight and replant them", "harvest"));
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            String item = e.getKey();
            if (farmlandFree && Crops.isSeed(item)) {
                int n = Math.min(e.getValue(), 8);
                out.add(option("plant_" + item, "plant " + n + " " + item + " on the empty farmland in sight", "plant", "seed", item, "count", n));
            }
        }
        boolean hoe = inv.keySet().stream().anyMatch(i -> i.endsWith("_hoe"));
        boolean seed = inv.keySet().stream().anyMatch(Crops::isSeed);
        if (hoe && seed && !farmlandFree) out.add(option("till_soil", "till 4 blocks of soil near water so seeds can be planted", "till", "count", 4));
        for (Sighting m : snap.mobs()) {
            if (m.count() < 2 || AnimalFood.foods(m.label()).stream().noneMatch(f -> inv.getOrDefault(f, 0) >= 2)) continue;
            out.add(option("breed_" + m.label(), "feed two grown " + m.label() + "s so they breed", "breed", "animal", m.label()));
        }
        boolean rod = inv.getOrDefault("fishing_rod", 0) > 0;
        boolean water = tags.contains(WATER_NEAR) || snap.blocks().stream().anyMatch(b -> b.label().equals("water") && b.distance() <= NEAR_RADIUS);
        if (rod && water) out.add(option("fish", "fish at the water nearby for 3 catches", "fish", "catches", 3));
        for (String raw : RAW_FOODS) {
            int held = inv.getOrDefault(raw, 0);
            if (held > 0) {
                out.add(option("cook_" + raw, "cook " + Math.min(held, 8) + " " + raw + " in a furnace", "smelt", "item", raw, "count", Math.min(held, 8)));
            }
        }
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
        SnapshotSection crops = new SnapshotSection("Crops", List.of("wheat x6 ripe (nearest 4m E)"), null,
                List.of(CROP_RIPE, FARMLAND_FREE, WATER_NEAR));
        return Samples.snapshot(List.of(), List.of(new Sighting("cow", 2, 3, "N")), List.of(crops));
    }

    @Override
    public OptionMenu.MenuContext sampleContext() {
        return Samples.context(Map.of("wheat_seeds", 12, "wheat", 2, "fishing_rod", 1, "beef", 3));
    }

    // ---------------------------------------------------------------- snapshot

    @Override
    public List<SnapshotSection> sections(AiPlayer bot, Attachments memory) {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        // One scan covers crops, bare farmland and water; each is classified afterwards.
        List<BlockPos> found = FarmScan.visible(bot, FarmModule::interesting, CROP_RADIUS);
        Map<String, int[]> crops = new LinkedHashMap<>(); // crop id -> {ripe, growing, nearest distance, dx, dz}
        int free = 0;
        int freeNear = 0;
        boolean waterNear = false;
        for (BlockPos p : found) {
            BlockState s = level.getBlockState(p);
            double d = Math.sqrt(p.distSqr(feet));
            if (s.getBlock() instanceof CropBlock crop) {
                String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                int[] c = crops.computeIfAbsent(id, k -> new int[] {0, 0, (int) Math.round(d), p.getX() - feet.getX(), p.getZ() - feet.getZ()});
                c[crop.isMaxAge(s) ? 0 : 1]++;
            } else if (s.is(Blocks.FARMLAND)) {
                if (!level.getBlockState(p.above()).isAir()) continue;
                free++;
                if (d <= NEAR_RADIUS) freeNear++;
            } else if (d <= NEAR_RADIUS) {
                waterNear = true;
            }
        }
        List<String> items = new ArrayList<>();
        boolean anyRipe = false;
        for (Map.Entry<String, int[]> e : crops.entrySet()) {
            int[] c = e.getValue();
            anyRipe |= c[0] > 0;
            List<String> parts = new ArrayList<>();
            if (c[0] > 0) parts.add("x" + c[0] + " ripe");
            if (c[1] > 0) parts.add("x" + c[1] + " growing");
            items.add(e.getKey() + " " + String.join(", ", parts) + " (nearest " + c[2] + "m " + Compass.direction(c[3], c[4]) + ")");
        }
        if (free > 0) items.add("free farmland x" + free);
        List<String> tags = new ArrayList<>();
        if (anyRipe) tags.add(CROP_RIPE);
        if (freeNear > 0) tags.add(FARMLAND_FREE);
        if (waterNear) tags.add(WATER_NEAR);
        return List.of(new SnapshotSection("Crops", items, null, tags));
    }

    private static boolean interesting(BlockState s) {
        return s.getBlock() instanceof CropBlock || s.is(Blocks.FARMLAND) || s.getFluidState().is(FluidTags.WATER);
    }
}
