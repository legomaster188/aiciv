package com.aiciv.world;

import com.aiciv.AiCiv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Remembers every block a player (human or bot) placed by hand, so bots never break what someone built. Saved as
 * {@code <world>/aiciv/placed.json}. Server thread only.
 */
public final class PlacedBlocks {
    public static final int CAPACITY = 250_000;

    private static PlacedBlocks current;

    private final MinecraftServer server;
    private final Path file;
    private final PlacementStore store;

    private PlacedBlocks(MinecraftServer server, Path file, PlacementStore store) {
        this.server = server;
        this.file = file;
        this.store = store;
    }

    /** The running server's instance, loaded from its world on first use. */
    public static synchronized PlacedBlocks of(MinecraftServer server) {
        if (current == null || current.server != server) current = load(server);
        return current;
    }

    private static PlacedBlocks load(MinecraftServer server) {
        Path file = WorldFiles.file(server, "placed.json");
        PlacementStore store = new PlacementStore(CAPACITY);
        if (Files.exists(file)) {
            try {
                store = PlacementStore.fromJson(Files.readString(file, StandardCharsets.UTF_8), CAPACITY);
            } catch (IOException | RuntimeException e) {
                AiCiv.LOG.warn("AiCiv could not read {}; starting with no placed blocks", file, e);
            }
        }
        return new PlacedBlocks(server, file, store);
    }

    /** Records the block now at {@code pos}, and the other half of a door, tall plant or bed. */
    public void record(ServerLevel level, BlockPos pos, String placer) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        long tick = level.getGameTime();
        put(level, pos, state, placer, tick);
        BlockPos other = null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        } else if (state.getBlock() instanceof AbstractBedBlock) {
            other = pos.relative(AbstractBedBlock.getConnectedDirection(state));
        }
        if (other != null) {
            BlockState otherState = level.getBlockState(other);
            if (otherState.is(state.getBlock())) put(level, other, otherState, placer, tick);
        }
    }

    private void put(ServerLevel level, BlockPos pos, BlockState state, String placer, long tick) {
        store.put(dimension(level), pos.asLong(), new PlacementStore.Placement(id(state), placer, tick));
    }

    /** Present only while the block there is still the one that was placed; a stale entry is forgotten. */
    public Optional<PlacementStore.Placement> at(ServerLevel level, BlockPos pos) {
        String dimension = dimension(level);
        long key = pos.asLong();
        Optional<PlacementStore.Placement> p = store.get(dimension, key);
        if (p.isEmpty()) return p;
        if (!p.get().block().equals(id(level.getBlockState(pos)))) {
            store.remove(dimension, key);
            return Optional.empty();
        }
        return p;
    }

    public boolean isPlaced(ServerLevel level, BlockPos pos) {
        return at(level, pos).isPresent();
    }

    /** Blocks {@code placer} placed in this level at or after {@code tick} that are still there, oldest first. */
    public List<BlockPos> placedSince(ServerLevel level, String placer, long tick) {
        List<BlockPos> out = new ArrayList<>();
        for (long key : store.placedBy(dimension(level), placer, tick)) {
            BlockPos pos = BlockPos.of(key);
            if (isPlaced(level, pos)) out.add(pos);
        }
        return out;
    }

    /** Writes placed.json if anything changed since the last save. */
    public void save() {
        if (!store.dirty()) return;
        try {
            WorldFiles.writeAtomically(file, store.toJson());
            store.markClean();
        } catch (IOException e) {
            AiCiv.LOG.warn("AiCiv could not save {}", file, e);
        }
    }

    /** Saves and forgets this server's instance (server stopping). */
    public static synchronized void unload(MinecraftServer server) {
        if (current == null || current.server != server) return;
        current.save();
        current = null;
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    /** Block ids are kept without the namespace, like every other id AiCiv shows a model. */
    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }
}
