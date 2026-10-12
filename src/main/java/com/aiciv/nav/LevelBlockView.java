package com.aiciv.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Reads a loaded level for the pathfinder. Unloaded blocks count as solid and dangerous. */
public final class LevelBlockView implements BlockView {
    private final ServerLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LevelBlockView(ServerLevel level) {
        this.level = level;
    }

    private BlockState at(int x, int y, int z) {
        cursor.set(x, y, z);
        return level.isLoaded(cursor) ? level.getBlockState(cursor) : null;
    }

    @Override
    public boolean passable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s != null && s.getCollisionShape(level, cursor).isEmpty();
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
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public boolean water(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s != null && s.getFluidState().is(FluidTags.WATER);
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
