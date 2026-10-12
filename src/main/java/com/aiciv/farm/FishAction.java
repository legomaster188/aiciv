package com.aiciv.farm;

import com.aiciv.actions.Action;
import com.aiciv.actions.ActionStatus;
import com.aiciv.actions.Approach;
import com.aiciv.actions.BotContext;
import com.aiciv.actions.ItemGatherer;
import com.aiciv.bot.AiPlayer;
import com.aiciv.mixin.FishingHookAccessor;
import com.aiciv.world.Finder;
import com.aiciv.world.InventoryOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Casts a real fishing rod at open water, reels in when the bobber dips, and keeps what the line brings. */
public final class FishAction implements Action {
    static final int WATER_RADIUS = 8;
    static final double STAND_RADIUS = 4.0;
    static final int MAX_CELLS = 3;
    static final int RECAST_TICKS = 10;
    /** Ticks the hook may sit out of the water (flying, or on land) before it is reeled in and cast again elsewhere. */
    static final int DRY_TICKS = 100;
    static final int LOOT_TICKS = 80;

    private enum Phase { PICK, GO, CAST, WAIT, LOOT }

    private final int catches;
    private final List<String> caught = new ArrayList<>();
    private final ItemGatherer gatherer = new ItemGatherer(this::nearbyItems);
    private List<BlockPos> cells;
    private int cell;
    private Phase phase = Phase.PICK;
    private Approach approach;
    private int ticks;
    private int dry;
    private Map<Item, Integer> before = Map.of();
    private String result;

    public FishAction(int catches) {
        this.catches = catches;
    }

    @Override
    public ActionStatus tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (phase == Phase.PICK && !hasRod(p)) return ActionStatus.failed("needs a fishing rod");
        return switch (phase) {
            case PICK -> pick(p);
            case GO -> go(ctx);
            case CAST -> cast(p);
            case WAIT -> waitForBite(p);
            case LOOT -> collect(ctx);
        };
    }

    private static boolean hasRod(AiPlayer p) {
        return InventoryOps.count(p, s -> s.is(Items.FISHING_ROD)) > 0;
    }

    private ActionStatus pick(AiPlayer p) {
        cells = new ArrayList<>();
        for (BlockPos pos : Finder.visibleStates(p, s -> s.is(Blocks.WATER) && s.getFluidState().isSource(), WATER_RADIUS)) {
            if (p.level().getBlockState(pos.above()).isAir()) cells.add(pos);
            if (cells.size() == MAX_CELLS) break;
        }
        if (cells.isEmpty()) return ActionStatus.failed("no open water within " + WATER_RADIUS);
        startGo();
        return ActionStatus.RUNNING;
    }

    private void startGo() {
        approach = Approach.block(cells.get(cell), STAND_RADIUS);
        phase = Phase.GO;
    }

    private ActionStatus go(BotContext ctx) {
        Approach.Status s = approach.tick(ctx);
        if (s.state() == Approach.State.RUNNING) return ActionStatus.RUNNING;
        if (s.state() == Approach.State.FAILED) {
            if (++cell >= cells.size()) return ActionStatus.failed("cannot get to the water: " + s.reason());
            startGo();
            return ActionStatus.RUNNING;
        }
        phase = Phase.CAST;
        ticks = RECAST_TICKS; // no pause before the first cast
        return ActionStatus.RUNNING;
    }

    private ActionStatus cast(AiPlayer p) {
        if (!hasRod(p)) return rodGone();
        if (ticks++ < RECAST_TICKS) return ActionStatus.RUNNING;
        if (p.fishing != null) { // a leftover line: reel it in first
            useRod(p);
            return ActionStatus.RUNNING;
        }
        BlockPos water = cells.get(cell);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5));
        useRod(p);
        dry = 0;
        phase = Phase.WAIT;
        return ActionStatus.RUNNING;
    }

    private ActionStatus waitForBite(AiPlayer p) {
        FishingHook hook = p.fishing;
        if (hook == null) { // the line was lost: the rod left the hand, or the hook was removed
            if (!hasRod(p)) return rodGone();
            phase = Phase.CAST;
            ticks = 0;
            return ActionStatus.RUNNING;
        }
        if (hook.isInWater()) {
            dry = 0;
            if (((FishingHookAccessor) hook).aiciv$nibble() > 0) return reel(p);
            return ActionStatus.RUNNING;
        }
        if (++dry < DRY_TICKS) return ActionStatus.RUNNING;
        useRod(p); // the hook is on land or never reached water: reel it in and try another water cell
        cell = (cell + 1) % cells.size();
        startGo();
        return ActionStatus.RUNNING;
    }

    private ActionStatus reel(AiPlayer p) {
        before = nonRodCounts(p);
        useRod(p);
        ticks = 0;
        phase = Phase.LOOT;
        return ActionStatus.RUNNING;
    }

    /** The reeled-in item flies to the bot a few ticks later; wait for it, walking to it if it fell short. */
    private ActionStatus collect(BotContext ctx) {
        AiPlayer p = ctx.player();
        boolean got = false;
        for (Map.Entry<Item, Integer> e : nonRodCounts(p).entrySet()) {
            int gain = e.getValue() - before.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < gain; i++) {
                String name = BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
                caught.add(name);
                ctx.event("caught " + name);
                got = true;
            }
        }
        if (got || ++ticks > LOOT_TICKS) {
            gatherer.stop(ctx);
            if (!hasRod(p)) return rodGone();
            if (caught.size() >= catches) return finish();
            phase = Phase.CAST;
            ticks = 0;
            return ActionStatus.RUNNING;
        }
        gatherer.tick(ctx);
        return ActionStatus.RUNNING;
    }

    private List<ItemEntity> nearbyItems(BotContext ctx) {
        AiPlayer p = ctx.player();
        return p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6), ItemEntity::isAlive);
    }

    private ActionStatus rodGone() {
        if (caught.isEmpty()) return ActionStatus.failed("the rod broke");
        return finish();
    }

    private ActionStatus finish() {
        result = "caught " + caught.size() + ": " + String.join(", ", caught);
        return ActionStatus.DONE;
    }

    private static void useRod(AiPlayer p) {
        InventoryOps.selectBest(p, s -> s.is(Items.FISHING_ROD) ? 1 : 0);
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    private static Map<Item, Integer> nonRodCounts(AiPlayer p) {
        Map<Item, Integer> out = new HashMap<>();
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && !s.is(Items.FISHING_ROD)) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }

    @Override
    public void cancel(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p != null && p.fishing != null && hasRod(p)) useRod(p); // reel the line in
        gatherer.stop(ctx);
        if (approach != null) approach.cancel(ctx);
    }

    @Override
    public void interrupted(BotContext ctx) {
        AiPlayer p = ctx.player();
        if (p != null && p.fishing != null && hasRod(p)) useRod(p);
        approach = null;
        if (cells != null) startGo();
        else phase = Phase.PICK;
    }

    @Override
    public String describe() {
        return result != null ? result : "fish " + catches;
    }

    @Override
    public int timeoutTicks() {
        return 5 * 60 * 20;
    }
}
