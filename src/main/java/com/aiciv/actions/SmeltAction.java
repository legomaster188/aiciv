package com.aiciv.actions;

import com.aiciv.bot.AiPlayer;
import com.aiciv.craft.CraftExecutor;
import com.aiciv.craft.CraftPlanner;
import com.aiciv.craft.RecipeBook;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.Sight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Smelts {@code count} items in a furnace: finds one (or places a held one, or crafts one from cobblestone),
 * loads input and fuel, waits, and takes the results out as they appear. Leftovers go back to the inventory.
 */
public final class SmeltAction implements Action {
    static final int FIND_RADIUS = 16;
    static final double REACH = 4.0;
    static final double STAND_RADIUS = 3.0;
    static final String NO_FURNACE = "needs a furnace or 8 cobblestone";

    private enum Phase { PLAN, FURNACE, CRAFT, REACH, RUN }

    private final String query;
    private final int count;
    private Phase phase = Phase.PLAN;
    private Item input;
    private Item output;
    private BlockPos furnace;
    private CraftAction crafter;
    private boolean loaded;
    /** Whether this action started the walk to the furnace; until then the navigator's status is a previous action's. */
    private boolean walking;
    private int taken;
    private String doneSummary;

    public SmeltAction(String item, int count) {
        String q = item.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        this.query = q.replaceAll("\\s+", "_");
        this.count = count;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer player = ctx.player();
        return switch (phase) {
            case PLAN -> plan(player);
            case FURNACE -> findFurnace(ctx, player);
            case CRAFT -> craftFurnace(ctx, player);
            case REACH -> reach(ctx, player);
            case RUN -> run(ctx, player);
        };
    }

    // ---------------------------------------------------------------- planning

    private ActionStatus plan(AiPlayer player) {
        ServerLevel level = player.level();
        String singular = query.endsWith("s") ? query.substring(0, query.length() - 1) : query;
        Item bestInput = null;
        Item bestOutput = null;
        int bestHeld = 0;
        Map<Item, Integer> held = new LinkedHashMap<>();
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty()) held.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        for (Map.Entry<Item, Integer> e : held.entrySet()) {
            Item result = smeltsTo(level, e.getKey());
            if (result == null) continue;
            String in = idOf(e.getKey()), out = idOf(result);
            boolean matches = in.equals(query) || in.equals(singular) || out.equals(query) || out.equals(singular);
            if (matches && e.getValue() > bestHeld) {
                bestInput = e.getKey();
                bestOutput = result;
                bestHeld = e.getValue();
            }
        }
        if (bestInput == null) return ActionStatus.failed(nothingToSmelt(level));
        if (bestHeld < count) {
            return ActionStatus.failed("need " + count + " " + idOf(bestInput) + " to smelt " + count + " " + idOf(bestOutput) + " but only hold " + bestHeld);
        }
        input = bestInput;
        output = bestOutput;
        doneSummary = null;
        phase = Phase.FURNACE;
        return ActionStatus.RUNNING;
    }

    /** What the item smelts into, or null when no smelting recipe takes it. */
    private static Item smeltsTo(ServerLevel level, Item item) {
        SingleRecipeInput in = new SingleRecipeInput(new ItemStack(item));
        Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, in, level);
        return recipe.map(r -> r.value().assemble(in).getItem()).orElse(null);
    }

    /** Tells the model what to bring: the input items of recipes that make the wanted output. */
    private String nothingToSmelt(ServerLevel level) {
        List<String> inputs = new ArrayList<>();
        boolean known = false;
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (!(holder.value() instanceof SmeltingRecipe r)) continue;
            Item first = r.input().items().findFirst().map(h -> h.value()).orElse(null);
            if (first == null) continue;
            SingleRecipeInput in = new SingleRecipeInput(new ItemStack(first));
            String out = idOf(r.assemble(in).getItem());
            if (out.equals(query)) {
                known = true;
                if (inputs.size() < 3) inputs.add(idOf(first));
            } else if (idOf(first).equals(query)) {
                known = true;
            }
        }
        if (!known) return query + " cannot be smelted";
        return inputs.isEmpty()
                ? "hold some " + query + " to smelt it"
                : "hold " + String.join(" or ", inputs) + " to smelt into " + query;
    }

    // ---------------------------------------------------------------- the furnace

    private ActionStatus findFurnace(BotContext ctx, AiPlayer player) {
        ServerLevel level = player.level();
        BlockPos found = nearestFurnace(ctx, player);
        if (found != null) {
            furnace = found;
            phase = Phase.REACH;
            return ActionStatus.RUNNING;
        }
        if (InventoryOps.has(player, Items.FURNACE, 1)) return placeFurnace(ctx, player);
        List<com.aiciv.craft.RecipeOption> recipes = RecipeBook.load(level);
        Map<String, Integer> counts = CraftExecutor.inventoryCounts(player);
        if (CraftPlanner.canMake("furnace", 1, counts, recipes, false) || CraftPlanner.canMake("furnace", 1, counts, recipes, true)) {
            crafter = new CraftAction("furnace", 1);
            phase = Phase.CRAFT;
            return ActionStatus.RUNNING;
        }
        return ActionStatus.failed(NO_FURNACE);
    }

    private ActionStatus craftFurnace(BotContext ctx, AiPlayer player) {
        ActionStatus s = crafter.tick(ctx);
        if (s.state() == ActionStatus.State.FAILED) return ActionStatus.failed(NO_FURNACE + " (" + s.reason() + ")");
        if (s.state() == ActionStatus.State.DONE) return placeFurnace(ctx, player);
        return ActionStatus.RUNNING;
    }

    private ActionStatus placeFurnace(BotContext ctx, AiPlayer player) {
        BlockPos at = BlockPlacer.placeNearby(player, s -> s.is(Items.FURNACE));
        if (at == null) return ActionStatus.failed("no free spot next to me to place a furnace");
        if (ctx.knownBlocks() != null) ctx.knownBlocks().see("furnace", new Pos(at.getX(), at.getY(), at.getZ()), player.level().getGameTime());
        furnace = at;
        phase = Phase.RUN;
        return ActionStatus.RUNNING;
    }

    private BlockPos nearestFurnace(BotContext ctx, AiPlayer player) {
        ServerLevel level = player.level();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        Vec3 here = player.position();
        for (Map.Entry<Pos, String> e : Perceiver.exposedBlocks(level, player.blockPosition(), FIND_RADIUS, id -> id.equals("furnace"))) {
            BlockPos pos = new BlockPos(e.getKey().x(), e.getKey().y(), e.getKey().z());
            double d2 = Vec3.atCenterOf(pos).distanceToSqr(here);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = pos;
            }
        }
        if (ctx.knownBlocks() != null) {
            BlockPos feet = player.blockPosition();
            Optional<Pos> known = ctx.knownBlocks().nearest(id -> id.equals("furnace"), new Pos(feet.getX(), feet.getY(), feet.getZ()));
            if (known.isPresent()) {
                BlockPos pos = new BlockPos(known.get().x(), known.get().y(), known.get().z());
                if (level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.FURNACE)) {
                    ctx.knownBlocks().gone(known.get());
                } else if (level.getBlockState(pos).is(Blocks.FURNACE) && Vec3.atCenterOf(pos).distanceToSqr(here) < bestD2) {
                    best = pos;
                }
            }
        }
        return best;
    }

    /** Close enough to use the furnace, and able to see it: no reaching into a furnace behind a wall. */
    private boolean inReach(AiPlayer player) {
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(furnace)) <= REACH * REACH && Sight.canSee(player, furnace);
    }

    private ActionStatus reach(BotContext ctx, AiPlayer player) {
        if (inReach(player)) {
            ctx.navigator().stop();
            walking = false;
            phase = Phase.RUN;
            return ActionStatus.RUNNING;
        }
        if (!walking) {
            walking = true;
            ctx.navigator().goTo(new NearGoal(furnace.getX() + 0.5, furnace.getY() + 0.5, furnace.getZ() + 0.5, STAND_RADIUS));
            return ActionStatus.RUNNING;
        }
        NavStatus nav = ctx.navigator().status();
        switch (nav.state()) {
            case RUNNING:
                return ActionStatus.RUNNING;
            case FAILED:
                return ActionStatus.failed("cannot reach the furnace: " + nav.reason());
            case ARRIVED:
                return ActionStatus.failed("cannot get within reach and sight of the furnace at " + furnace.getX() + " " + furnace.getY() + " " + furnace.getZ());
            default:
                ctx.navigator().goTo(new NearGoal(furnace.getX() + 0.5, furnace.getY() + 0.5, furnace.getZ() + 0.5, STAND_RADIUS));
                return ActionStatus.RUNNING;
        }
    }

    // ---------------------------------------------------------------- smelting

    private ActionStatus run(BotContext ctx, AiPlayer player) {
        ServerLevel level = player.level();
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be)) {
            return ActionStatus.failed("the furnace at " + furnace.getX() + " " + furnace.getY() + " " + furnace.getZ() + " is gone");
        }
        if (!inReach(player)) {
            phase = Phase.REACH;
            walking = false;
            return ActionStatus.RUNNING;
        }
        if (!loaded) {
            String problem = load(player, be);
            if (problem != null) return fail(player, problem);
            loaded = true;
            return ActionStatus.RUNNING;
        }
        ItemStack out = be.getItem(2);
        if (!out.isEmpty() && out.is(output)) {
            taken += out.getCount();
            be.setItem(2, ItemStack.EMPTY);
            InventoryOps.addOrDrop(player, out);
        }
        if (taken >= count) {
            giveBack(player, be);
            doneSummary = "smelted " + taken + " " + idOf(output);
            return ActionStatus.DONE;
        }
        BlockState state = level.getBlockState(furnace);
        boolean lit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        if (!be.getItem(0).isEmpty() && be.getItem(1).isEmpty() && !lit) {
            return fail(player, "ran out of fuel after " + taken + " of " + count + " " + idOf(output));
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus fail(AiPlayer player, String reason) {
        if (player.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be) giveBack(player, be);
        return ActionStatus.failed(reason);
    }

    /** Puts input and fuel in. Returns a problem, or null when the furnace is running. */
    private String load(AiPlayer player, AbstractFurnaceBlockEntity be) {
        ItemStack out = be.getItem(2);
        if (!out.isEmpty()) {
            be.setItem(2, ItemStack.EMPTY);
            InventoryOps.addOrDrop(player, out);
        }
        ItemStack slot = be.getItem(0);
        int existing = 0;
        if (!slot.isEmpty() && !slot.is(input)) {
            be.setItem(0, ItemStack.EMPTY);
            InventoryOps.addOrDrop(player, slot);
        } else if (!slot.isEmpty()) {
            existing = slot.getCount();
        }
        Item fuel = chooseFuel(player, be);
        if (fuel == null) return "no fuel: hold coal, charcoal, logs or planks to burn";
        int need = Math.max(0, Math.min(count, input.getDefaultMaxStackSize()) - existing);
        InventoryOps.remove(player, s -> s.is(input), need);
        be.setItem(0, new ItemStack(input, existing + need));

        ItemStack fuelSlot = be.getItem(1);
        int wanted = fuelNeeded(fuel);
        int present = fuelSlot.isEmpty() ? 0 : fuelSlot.getCount();
        int add = Math.min(Math.max(0, wanted - present), InventoryOps.count(player, s -> s.is(fuel)));
        if (add > 0) {
            InventoryOps.remove(player, s -> s.is(fuel), add);
            be.setItem(1, new ItemStack(fuel, present + add));
        }
        return null;
    }

    /** Coal and charcoal first, then logs and planks, then anything else the furnace accepts; never the input. */
    private Item chooseFuel(AiPlayer player, AbstractFurnaceBlockEntity be) {
        ItemStack inSlot = be.getItem(1);
        if (!inSlot.isEmpty()) {
            if (inSlot.is(input)) return null;
            return inSlot.getItem();
        }
        Map<Item, Integer> held = new LinkedHashMap<>();
        for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty() || s.is(input) || !be.canPlaceItem(1, s)) continue;
            held.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        // A fuel that covers the whole job comes first, then the better-ranked fuel, then the one that goes furthest.
        return held.entrySet().stream()
                .min(Comparator
                        .comparing((Map.Entry<Item, Integer> e) -> e.getValue() < fuelNeeded(e.getKey()))
                        .thenComparingInt(e -> rank(e.getKey()))
                        .thenComparing(e -> -coverage(e.getKey(), e.getValue())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static double coverage(Item fuel, int held) {
        return held * itemsPerFuel(fuel);
    }

    private static int rank(Item fuel) {
        String id = idOf(fuel);
        if (id.equals("coal") || id.equals("charcoal")) return 0;
        if (id.endsWith("_log") || id.endsWith("_planks") || id.endsWith("_wood") || id.endsWith("_stem")) return 1;
        return 2;
    }

    /** How many items one fuel item smelts. */
    private static double itemsPerFuel(Item fuel) {
        String id = idOf(fuel);
        return switch (id) {
            case "coal", "charcoal" -> 8;
            case "coal_block" -> 80;
            case "blaze_rod" -> 12;
            case "lava_bucket" -> 100;
            case "stick" -> 0.5;
            default -> rank(fuel) == 1 ? 1.5 : 1;
        };
    }

    private int fuelNeeded(Item fuel) {
        return (int) Math.ceil(count / itemsPerFuel(fuel)) + 1;
    }

    /** Takes anything of ours still in the furnace back: unsmelted input, unburnt fuel and finished output. */
    private void giveBack(AiPlayer player, AbstractFurnaceBlockEntity be) {
        for (int slot = 0; slot <= 2; slot++) {
            ItemStack s = be.getItem(slot);
            if (s.isEmpty()) continue;
            be.setItem(slot, ItemStack.EMPTY);
            if (slot == 2 && s.is(output)) taken += s.getCount();
            InventoryOps.addOrDrop(player, s);
        }
    }

    private static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    @Override
    public void cancel(BotContext ctx) {
        ctx.navigator().stop();
        AiPlayer player = ctx.player();
        // A dead bot, or one too far away to open the furnace, leaves its items in it; they can be fetched later.
        if (!loaded || furnace == null || player.isDeadOrDying() || !inReach(player)) return;
        if (player.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be) giveBack(player, be);
    }

    @Override
    public void interrupted(BotContext ctx) {
        walking = false; // the reflex stopped the walk; start it again from wherever the bot ends up
    }

    @Override
    public String describe() {
        return doneSummary != null ? doneSummary : "smelt " + count + " " + (output != null ? idOf(output) : query);
    }

    @Override
    public int timeoutTicks() {
        return 5 * 60 * 20;
    }
}
