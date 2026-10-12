package com.aiciv.farm;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.ItemNames;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

/** Plants a seed on empty farmland in sight, one block at a time, until the count or the seeds run out. */
public final class PlantAction extends BlockClickAction {
    static final int RADIUS = 8;

    private final String requested;
    private Item seed;
    private String seedId;
    private String crop;

    public PlantAction(String seed, int count) {
        super(count);
        this.requested = seed;
    }

    @Override
    String start(AiPlayer p) {
        seed = ItemNames.item(requested);
        seedId = seed == null ? "" : BuiltInRegistries.ITEM.getKey(seed).getPath();
        if (!Crops.isSeed(seedId)) return requested + " is not a seed; use wheat_seeds, carrot, potato, beetroot_seeds...";
        crop = Crops.cropFor(seedId).orElseThrow();
        if (InventoryOps.count(p, s -> s.is(seed)) == 0) return "you hold no " + seedId;
        return null;
    }

    @Override
    int limit(AiPlayer p) {
        return Math.min(count, succeeded + InventoryOps.count(p, s -> s.is(seed)));
    }

    @Override
    List<BlockPos> candidates(AiPlayer p) {
        ServerLevel level = p.level();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : FarmScan.visible(p, s -> s.is(Blocks.FARMLAND), RADIUS)) {
            if (level.getBlockState(pos.above()).isAir()) out.add(pos);
        }
        return out;
    }

    @Override
    boolean click(AiPlayer p, BlockPos pos) {
        if (!InventoryOps.selectBest(p, s -> s.is(seed) ? 1 : 0)) return false;
        BlockUse.clickFace(p, pos, Direction.UP);
        return BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(pos.above()).getBlock()).getPath().equals(crop);
    }

    @Override
    String summary(BotContext ctx) {
        return "planted " + succeeded + " " + seedId;
    }

    @Override
    String nothingToDo() {
        return "no empty farmland within " + RADIUS + " — till some first";
    }

    @Override
    String label() {
        return "plant " + (seedId == null || seedId.isEmpty() ? requested : seedId);
    }
}
