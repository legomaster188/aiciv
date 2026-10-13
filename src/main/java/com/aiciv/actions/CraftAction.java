package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.craft.CraftExecutor;
import com.aiciv.craft.CraftPlanner;
import com.aiciv.craft.RecipeBook;
import com.aiciv.craft.RecipeOption;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.Sight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Crafts {@code count} of an item, making intermediates (planks, sticks) on the way and placing a crafting
 * table next to the bot when a recipe needs one and none is within 4 blocks. One step every 5 ticks.
 */
public final class CraftAction implements Action {
    static final int TICKS_PER_STEP = 5;
    static final int TABLE_RADIUS = 4;
    static final int STEP_TIMEOUT_TICKS = 30 * 20;
    static final int MAX_TIMEOUT_TICKS = 3 * 60 * 20;

    private String item;
    private final int count;
    private List<CraftPlanner.Step> steps = List.of();
    private int next;
    private int wait;
    private boolean planned;
    private int goal;
    private String doneSummary;

    public CraftAction(String item, int count) {
        this.item = normalise(item);
        this.count = count;
    }

    /** Item names keep their plural "s" (iron_boots, glass_panes): unlike block names, only case and spaces change. */
    private static String normalise(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        return q.replaceAll("\\s+", "_");
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        if (!planned) return plan(ctx);
        if (next >= steps.size()) return finish(ctx);
        if (++wait < TICKS_PER_STEP) return ActionStatus.RUNNING;
        wait = 0;
        AiPlayer player = ctx.player();
        CraftPlanner.Step step = steps.get(next);
        if (step.recipe().needsTable() && !tableNearby(player)) {
            String problem = placeTable(ctx, player);
            if (problem != null) return ActionStatus.failed(problem);
        }
        if (!CraftExecutor.apply(player, step)) {
            return ActionStatus.failed("could not craft " + step.recipe().output() + " — the ingredients were not all in the inventory");
        }
        next++;
        return ActionStatus.RUNNING;
    }

    // ---------------------------------------------------------------- planning

    private ActionStatus plan(BotContext ctx) {
        planned = true;
        AiPlayer player = ctx.player();
        item = resolve(player, item);
        if (!exists(item)) return ActionStatus.failed("no item called " + item);
        List<RecipeOption> recipes = RecipeBook.load(player.level());
        if (recipes.stream().noneMatch(r -> r.output().equals(item))) {
            return ActionStatus.failed(item + " has no crafting recipe — it has to be mined, smelted or found");
        }
        Map<String, Integer> inventory = CraftExecutor.inventoryCounts(player);
        goal = inventory.getOrDefault(item, 0) + count;
        CraftPlanner.Plan plan = CraftPlanner.plan(item, goal, inventory, recipes, tableNearby(player));
        if (!plan.missing().isEmpty()) {
            List<String> parts = new ArrayList<>();
            plan.missing().forEach((name, n) -> parts.add(n + " more " + name));
            return ActionStatus.failed("missing " + String.join(", ", parts));
        }
        steps = plan.steps();
        return ActionStatus.RUNNING;
    }

    private static boolean exists(String id) {
        Identifier key = Identifier.tryParse("minecraft:" + id);
        return key != null && BuiltInRegistries.ITEM.containsKey(key);
    }

    /** Models say "sticks" and "planks"; map them to real item ids, preferring the planks the bot already holds. */
    private static String resolve(AiPlayer player, String name) {
        if (exists(name)) return name;
        if (name.equals("planks") || name.equals("wood_planks")) {
            for (Map.Entry<String, Integer> e : CraftExecutor.inventoryCounts(player).entrySet()) {
                if (e.getKey().endsWith("_planks")) return e.getKey();
            }
            return "oak_planks";
        }
        if (name.endsWith("s") && exists(name.substring(0, name.length() - 1))) return name.substring(0, name.length() - 1);
        return name;
    }

    private ActionStatus finish(BotContext ctx) {
        AiPlayer player = ctx.player();
        int held = InventoryOps.count(player, s -> RecipeBook.idOf(s.getItem()).equals(item));
        if (held < goal) return ActionStatus.failed("crafted everything planned but only hold " + held + " " + item);
        Map<String, Integer> made = new LinkedHashMap<>();
        for (CraftPlanner.Step s : steps) {
            if (!s.recipe().output().equals(item)) made.merge(s.recipe().output(), s.times() * s.recipe().outputCount(), Integer::sum);
        }
        StringBuilder text = new StringBuilder("crafted ").append(count).append(' ').append(item);
        if (!made.isEmpty()) {
            List<String> parts = new ArrayList<>();
            made.forEach((name, n) -> parts.add(n + " " + name));
            text.append(" (made ").append(String.join(", ", parts)).append(" on the way)");
        }
        doneSummary = text.toString();
        return ActionStatus.DONE;
    }

    // ---------------------------------------------------------------- the table

    public static boolean tableNearby(AiPlayer player) {
        return nearestTable(player) != null;
    }

    private static BlockPos nearestTable(AiPlayer player) {
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        Vec3 eyes = player.getEyePosition();
        BlockPos best = null;
        double bestD2 = (TABLE_RADIUS + 0.5) * (TABLE_RADIUS + 0.5);
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-TABLE_RADIUS, -TABLE_RADIUS, -TABLE_RADIUS),
                feet.offset(TABLE_RADIUS, TABLE_RADIUS, TABLE_RADIUS))) {
            if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double d2 = Vec3.atCenterOf(pos).distanceToSqr(eyes);
            if (d2 <= bestD2 && Sight.canSee(player, pos)) { // a table behind a wall cannot be used
                bestD2 = d2;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Puts a held crafting table on a free floor cell next to the bot. Returns a problem, or null once it is down. */
    private String placeTable(BotContext ctx, AiPlayer player) {
        if (InventoryOps.count(player, s -> s.is(Items.CRAFTING_TABLE)) < 1) return "no crafting table to place";
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos cell = feet.offset(dx, 0, dz);
                if (level.getBlockState(cell).canBeReplaced() && level.getFluidState(cell).isEmpty()
                        && !level.getBlockState(cell.below()).canBeReplaced()) {
                    cells.add(cell);
                }
            }
        }
        cells.sort(Comparator.comparingDouble(c -> c.distSqr(feet)));
        for (BlockPos cell : cells) {
            if (BlockPlacer.place(player, cell, s -> s.is(Items.CRAFTING_TABLE)) == BlockPlacer.PlaceResult.PLACED) {
                if (ctx.knownBlocks() != null) ctx.knownBlocks().see("crafting_table", new Pos(cell.getX(), cell.getY(), cell.getZ()), level.getGameTime());
                return null;
            }
        }
        return "no free spot next to me to place a crafting table";
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "craft " + item + " x" + count;
    }

    @Override
    public int timeoutTicks() {
        return Math.min(MAX_TIMEOUT_TICKS, Math.max(1, steps.size()) * STEP_TIMEOUT_TICKS);
    }
}
