package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BreakTime;
import com.aiciv.world.InventoryOps;
import com.aiciv.world.PlacedBlocks;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Reads a loaded level for the pathfinder. Unloaded blocks count as solid and dangerous. */
public final class LevelBlockView implements BlockView {
    private final ServerLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private final AiPlayer player;
    /** Break time by block state: the planner asks about the same few states over and over. */
    private final Map<BlockState, Double> breakTimes = new HashMap<>();

    /** A view that never breaks anything. */
    public LevelBlockView(ServerLevel level) {
        this(level, null);
    }

    public LevelBlockView(ServerLevel level, AiPlayer player) {
        this.level = level;
        this.player = player;
    }

    private BlockState at(int x, int y, int z) {
        cursor.set(x, y, z);
        return level.isLoaded(cursor) ? level.getBlockState(cursor) : null;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s == null) return false;
        // An open door only has a thin slab on one edge; the walker goes down the middle.
        return s.getCollisionShape(level, cursor).isEmpty() || isOpenDoor(s);
    }

    private static boolean isOpenDoor(BlockState s) {
        return s.is(BlockTags.WOODEN_DOORS) && s.hasProperty(BlockStateProperties.OPEN) && s.getValue(BlockStateProperties.OPEN);
    }

    @Override
    public boolean openable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s != null && (s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.FENCE_GATES))
                && s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN);
    }

    @Override
    public boolean standable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s == null) return false;
        VoxelShape shape = s.getCollisionShape(level, cursor);
        // Fences and walls are taller than a block: you cannot stand on them at y + 1.
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
    }

    @Override
    public double breakSeconds(int x, int y, int z) {
        if (player == null) return Double.POSITIVE_INFINITY;
        BlockState s = at(x, y, z);
        if (s == null || !s.getFluidState().isEmpty() || s.hasBlockEntity() || dangerous(x, y, z)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES)) {
            return Double.POSITIVE_INFINITY;
        }
        // Someone else built this; a bot walks around it rather than through it.
        if (PlacedBlocks.of(level.getServer()).isPlacedByOther(level, cursor, player.getScoreboardName())) return Double.POSITIVE_INFINITY;
        Double cached = breakTimes.get(s);
        if (cached != null) return cached;
        double seconds = BreakTime.seconds(s, s.getDestroySpeed(level, cursor), InventoryOps.bestToolFor(player, s));
        breakTimes.put(s, seconds);
        return seconds;
    }

    @Override
    public boolean water(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s != null && s.getFluidState().is(FluidTags.WATER) && s.getCollisionShape(level, cursor).isEmpty();
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s == null) return true;
        return s.getFluidState().is(FluidTags.LAVA)
                || s.is(BlockTags.FIRE)
                || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CACTUS)
                || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE)
                || s.is(Blocks.WITHER_ROSE);
    }
}
