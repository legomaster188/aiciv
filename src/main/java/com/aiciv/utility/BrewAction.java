package com.aiciv.utility;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.MenuOps;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Brews {@code count} bottles of a potion: awkward first when the potion needs it, one stage at a time through the
 * stand's menu, waiting beside the stand while it brews.
 */
public final class BrewAction implements Action {
    static final int FIND_RADIUS = 16;
    static final double STAND_REACH = 3.5;
    static final double WATER_RANGE = 4.0;
    static final int STAGE_WAIT = 700;
    private static final int SLOT_FUEL = 4, SLOT_INGREDIENT = 3;

    private enum Phase { PLAN, STAND, WATER, WALK, LOAD, WAIT, COLLECT }

    /** One pass through the stand: {@code bottles} bottles of {@code base} become {@code result} with {@code ingredient}. */
    private record Stage(Holder<Potion> base, Holder<Potion> result, Item ingredient, int bottles) {}

    private final String potion;
    private final int count;
    private Phase phase = Phase.PLAN;
    private Phase afterWalk = Phase.LOAD;
    private final List<Stage> stages = new ArrayList<>();
    private int stage;
    private BlockPos stand;
    private Approach approach;
    private int fillTries;
    private int waited;
    private String done;
    /** Something of the bot's is in the stand (bottles, ingredient or fuel it put there), to take back if it stops early. */
    private boolean loaded;
    private boolean fuelPlaced;

    public BrewAction(String potion, int count) {
        this.potion = BrewTable.normalise(potion);
        this.count = Math.max(1, Math.min(3, count));
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        return switch (phase) {
            case PLAN -> plan(p);
            case STAND -> findStand(ctx, p);
            case WATER -> fillBottles(p);
            case WALK -> walk(ctx);
            case LOAD -> load(ctx, p);
            case WAIT -> waitForStage(ctx, p);
            case COLLECT -> collect(ctx, p);
        };
    }

    // ---------------------------------------------------------------- planning

    private ActionStatus plan(AiPlayer p) {
        Optional<String> ingredient = BrewTable.ingredientFor(potion);
        Holder<Potion> target = holder(potion);
        if (ingredient.isEmpty() || target == null) {
            return ActionStatus.failed("can't brew '" + potion + "'; try awkward, swiftness, strength, healing, fire_resistance, night_vision, "
                    + "water_breathing, regeneration, leaping or slow_falling");
        }
        int awkwardHeld = Math.min(count, held(p, Potions.AWKWARD));
        int waterHeld = held(p, Potions.WATER);
        int glass = InventoryOps.count(p, s -> s.is(Items.GLASS_BOTTLE));
        if (!BrewTable.needsAwkwardBase(potion)) {
            stages.add(new Stage(Potions.WATER, Potions.AWKWARD, item(ingredient.get()), count));
        } else {
            int missing = count - awkwardHeld;
            if (missing > 0) stages.add(new Stage(Potions.WATER, Potions.AWKWARD, item("nether_wart"), missing));
            stages.add(new Stage(Potions.AWKWARD, target, item(ingredient.get()), count));
        }
        Stage first = stages.get(0);
        if (first.base() == Potions.WATER && waterHeld + glass < first.bottles()) {
            return ActionStatus.failed("needs " + first.bottles() + " water bottles or glass bottles and water");
        }
        for (Stage s : stages) {
            if (InventoryOps.count(p, x -> x.is(s.ingredient())) < 1) return ActionStatus.failed("needs " + id(s.ingredient()));
        }
        phase = Phase.STAND;
        return ActionStatus.RUNNING;
    }

    private ActionStatus findStand(BotContext ctx, AiPlayer p) {
        Optional<BlockPos> found = Finder.nearestBlock(ctx, id -> id.equals("brewing_stand"), FIND_RADIUS);
        if (found.isPresent()) {
            stand = found.get();
        } else if (InventoryOps.has(p, Items.BREWING_STAND, 1)) {
            stand = BlockPlacer.placeNearby(p, s -> s.is(Items.BREWING_STAND));
            if (stand == null) return ActionStatus.failed("no free spot next to me to place a brewing stand");
        } else {
            return ActionStatus.failed("no brewing stand within " + FIND_RADIUS + " blocks and none in your inventory");
        }
        Stage first = stages.get(0);
        phase = first.base() == Potions.WATER && held(p, Potions.WATER) < first.bottles() ? Phase.WATER : Phase.WALK;
        afterWalk = Phase.LOAD;
        return ActionStatus.RUNNING;
    }

    // ---------------------------------------------------------------- water

    /** Fills glass bottles from a water source the bot can see, one use per tick. */
    private ActionStatus fillBottles(AiPlayer p) {
        Stage first = stages.get(0);
        int need = first.bottles() - held(p, Potions.WATER);
        if (need <= 0) {
            phase = Phase.WALK;
            return ActionStatus.RUNNING;
        }
        String missing = "needs " + first.bottles() + " water bottles or glass bottles and water";
        if (InventoryOps.count(p, s -> s.is(Items.GLASS_BOTTLE)) < 1 || ++fillTries > 40) return ActionStatus.failed(missing);
        ServerLevel level = p.level();
        List<BlockPos> water = Finder.visibleStates(p, s -> s.getFluidState().isSource() && s.getFluidState().is(FluidTags.WATER), (int) WATER_RANGE);
        if (water.isEmpty()) return ActionStatus.failed(missing);
        BlockPos at = water.get(0);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(at.getX() + 0.5, at.getY() + 0.7, at.getZ() + 0.5));
        InventoryOps.selectBest(p, s -> s.is(Items.GLASS_BOTTLE) ? 1 : 0);
        int before = held(p, Potions.WATER);
        p.gameMode.useItem(p, level, p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        if (held(p, Potions.WATER) > before) fillTries = 0;
        return ActionStatus.RUNNING;
    }

    // ---------------------------------------------------------------- walking

    private ActionStatus walk(BotContext ctx) {
        if (approach == null) approach = Approach.block(stand, STAND_REACH);
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        approach = null;
        if (s.state() == Approach.State.FAILED) return ActionStatus.failed(s.reason());
        phase = afterWalk;
        return ActionStatus.RUNNING;
    }

    private boolean near(AiPlayer p) {
        return p.getEyePosition().distanceToSqr(Vec3.atCenterOf(stand)) <= (STAND_REACH + 1.0) * (STAND_REACH + 1.0);
    }

    // ---------------------------------------------------------------- the stand

    private ActionStatus load(BotContext ctx, AiPlayer p) {
        if (!near(p)) return again(Phase.LOAD);
        Stage st = stages.get(stage);
        BrewingStandMenu menu = MenuOps.open(p, stand, BrewingStandMenu.class);
        if (menu == null) return ActionStatus.failed("could not open the brewing stand");
        try {
            if (!clearLeftovers(ctx, p, menu)) return ActionStatus.failed("the brewing stand is already in use");
            if (menu.getFuel() <= 0 && !menu.getSlot(SLOT_FUEL).hasItem()) {
                if (!placeOne(p, menu, s -> s.is(Items.BLAZE_POWDER), SLOT_FUEL)) return ActionStatus.failed("needs blaze_powder as fuel");
                fuelPlaced = true;
                markLoaded(ctx);
            }
            for (int i = 0; i < st.bottles(); i++) {
                if (!placeOne(p, menu, s -> isPotion(s, st.base()), i)) {
                    return failTakingBack(ctx, p, menu, "needs " + st.bottles() + " bottles of " + name(st.base()));
                }
                markLoaded(ctx);
            }
            if (!placeOne(p, menu, s -> s.is(st.ingredient()), SLOT_INGREDIENT)) return failTakingBack(ctx, p, menu, "needs " + id(st.ingredient()));
        } finally {
            MenuOps.close(p);
        }
        waited = 0;
        phase = Phase.WAIT;
        return ActionStatus.RUNNING;
    }

    private ActionStatus waitForStage(BotContext ctx, AiPlayer p) {
        if (!(p.level().getBlockEntity(stand) instanceof BrewingStandBlockEntity be)) return ActionStatus.failed("the brewing stand is gone");
        if (!near(p)) return again(Phase.WAIT);
        Stage st = stages.get(stage);
        boolean ready = true;
        for (int i = 0; i < st.bottles(); i++) ready &= isPotion(be.getItem(i), st.result());
        if (ready) {
            phase = Phase.COLLECT;
            return ActionStatus.RUNNING;
        }
        if (++waited > STAGE_WAIT) {
            giveBack(ctx, p);
            return ActionStatus.failed("the brewing stand stalled (is it out of fuel?)");
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus collect(BotContext ctx, AiPlayer p) {
        if (!near(p)) return again(Phase.COLLECT);
        Stage st = stages.get(stage);
        BrewingStandMenu menu = MenuOps.open(p, stand, BrewingStandMenu.class);
        if (menu == null) return ActionStatus.failed("could not open the brewing stand");
        boolean left = false;
        try {
            for (int i = 0; i < 3; i++) {
                if (menu.getSlot(i).hasItem()) menu.clicked(i, 0, ContainerInput.QUICK_MOVE, p);
                left |= menu.getSlot(i).hasItem();
            }
        } finally {
            MenuOps.close(p);
        }
        if (left) return ActionStatus.failed("no room in the inventory for the potions; they are still in the brewing stand");
        if (++stage < stages.size()) {
            phase = Phase.LOAD;
            return ActionStatus.RUNNING;
        }
        forgetLoaded(ctx); // the ingredient and the fuel item are used up; nothing of the bot's is left in the stand
        done = "brewed " + count + " potion of " + potion;
        return ActionStatus.DONE;
    }

    // ---------------------------------------------------------------- leftovers and taking back

    /**
     * Before loading: a stand that is not brewing may still hold finished potions (anyone may take those, as in
     * vanilla) and this bot's own leftovers from a brew it did not finish; both are collected first. False when the
     * stand is still in use (brewing, or holding someone else's ingredient) or what is in it does not fit.
     */
    private boolean clearLeftovers(BotContext ctx, AiPlayer p, BrewingStandMenu menu) {
        boolean any = false;
        for (int i = 0; i < SLOT_FUEL; i++) any |= menu.getSlot(i).hasItem();
        if (!any) return true;
        if (menu.getBrewingTicks() > 0) return false;
        boolean mine = loadedStands(ctx).contains(stand);
        for (int i = 0; i < SLOT_FUEL; i++) {
            if (menu.getSlot(i).hasItem() && (mine || i < SLOT_INGREDIENT)) menu.clicked(i, 0, ContainerInput.QUICK_MOVE, p);
        }
        for (int i = 0; i < SLOT_FUEL; i++) if (menu.getSlot(i).hasItem()) return false;
        if (mine) forgetLoaded(ctx);
        return true;
    }

    private ActionStatus failTakingBack(BotContext ctx, AiPlayer p, BrewingStandMenu menu, String reason) {
        takeBack(ctx, p, menu);
        return ActionStatus.failed(reason);
    }

    /** Takes back what the bot put in, through the stand's menu, when it is alive and can reach and see the stand. */
    private void giveBack(BotContext ctx, AiPlayer p) {
        if (!loaded || stand == null || p.isDeadOrDying() || !near(p)) return; // left in the stand; the next brew here collects it
        if (p.containerMenu instanceof BrewingStandMenu open) {
            takeBack(ctx, p, open);
            return;
        }
        BrewingStandMenu menu = MenuOps.open(p, stand, BrewingStandMenu.class);
        if (menu == null) return;
        try {
            takeBack(ctx, p, menu);
        } finally {
            MenuOps.close(p);
        }
    }

    private void takeBack(BotContext ctx, AiPlayer p, BrewingStandMenu menu) {
        boolean left = false;
        int last = fuelPlaced ? SLOT_FUEL : SLOT_INGREDIENT; // fuel someone else left there stays
        for (int i = 0; i <= last; i++) {
            if (menu.getSlot(i).hasItem()) menu.clicked(i, 0, ContainerInput.QUICK_MOVE, p);
            left |= menu.getSlot(i).hasItem();
        }
        if (!left) forgetLoaded(ctx);
    }

    private void markLoaded(BotContext ctx) {
        loaded = true;
        loadedStands(ctx).add(stand);
    }

    private void forgetLoaded(BotContext ctx) {
        loaded = false;
        loadedStands(ctx).remove(stand);
    }

    /** The stands this bot left something in, kept between actions so a later brew may take its own leftovers. */
    private static Set<BlockPos> loadedStands(BotContext ctx) {
        return ctx.attachments() == null ? new HashSet<>() : ctx.attachments().get(LoadedStands.class, LoadedStands::new).stands;
    }

    /** Per-bot memory of loaded brewing stands. */
    static final class LoadedStands {
        final Set<BlockPos> stands = new HashSet<>();
    }

    /** Walk back to the stand, then carry on with {@code next}. */
    private ActionStatus again(Phase next) {
        afterWalk = next;
        phase = Phase.WALK;
        return ActionStatus.RUNNING;
    }

    /** Puts one item that passes {@code match} into the menu slot: pick the stack up, right-click one in, put the rest back. */
    private static boolean placeOne(AiPlayer p, BrewingStandMenu menu, Predicate<ItemStack> match, int dst) {
        List<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !match.test(s)) continue;
            OptionalInt src = MenuOps.inventorySlot(menu, p, i);
            if (src.isEmpty()) continue;
            menu.clicked(src.getAsInt(), 0, ContainerInput.PICKUP, p);
            menu.clicked(dst, 1, ContainerInput.PICKUP, p);
            if (!menu.getCarried().isEmpty()) menu.clicked(src.getAsInt(), 0, ContainerInput.PICKUP, p);
            return menu.getSlot(dst).hasItem();
        }
        return false;
    }

    // ---------------------------------------------------------------- items

    private static int held(AiPlayer p, Holder<Potion> potion) {
        return InventoryOps.count(p, s -> isPotion(s, potion));
    }

    private static boolean isPotion(ItemStack s, Holder<Potion> potion) {
        if (s.isEmpty() || !s.is(Items.POTION)) return false;
        PotionContents contents = s.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(potion);
    }

    private static Holder<Potion> holder(String name) {
        return switch (name) {
            case "awkward" -> Potions.AWKWARD;
            case "swiftness" -> Potions.SWIFTNESS;
            case "strength" -> Potions.STRENGTH;
            case "healing" -> Potions.HEALING;
            case "fire_resistance" -> Potions.FIRE_RESISTANCE;
            case "night_vision" -> Potions.NIGHT_VISION;
            case "water_breathing" -> Potions.WATER_BREATHING;
            case "regeneration" -> Potions.REGENERATION;
            case "leaping" -> Potions.LEAPING;
            case "slow_falling" -> Potions.SLOW_FALLING;
            default -> null;
        };
    }

    private static String name(Holder<Potion> potion) {
        return potion == Potions.WATER ? "water" : potion == Potions.AWKWARD ? "awkward" : "potion";
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id));
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    @Override
    public void cancel(BotContext ctx) {
        if (approach != null) approach.cancel(ctx);
        AiPlayer p = ctx.player();
        if (p == null) return;
        giveBack(ctx, p); // cancel, timeout and replace all end here: the bot's bottles and ingredient come back with it
        if (p.containerMenu instanceof BrewingStandMenu) MenuOps.close(p);
    }

    @Override
    public void interrupted(BotContext ctx) {
        approach = null; // the walk is planned again; every stand phase re-checks the distance itself
    }

    @Override
    public String describe() {
        return done != null ? done : "brew " + potion + " x" + count;
    }

    @Override
    public int timeoutTicks() {
        return 3600;
    }
}
