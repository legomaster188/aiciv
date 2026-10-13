package com.aiciv.world;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.nav.Pos;
import com.aiciv.perception.Perceiver;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds blocks and entities the fair way: what the bot can see now, or remembers having seen. */
public final class Finder {
    private Finder() {}

    /**
     * The nearest block whose id (no namespace) passes {@code blockId}, within {@code radius}: one the bot can see from
     * its eyes, or a remembered one that is still that block.
     */
    public static Optional<BlockPos> nearestBlock(BotContext ctx, Predicate<String> blockId, int radius) {
        AiPlayer p = ctx.player();
        ServerLevel level = p.level();
        Vec3 eyes = p.getEyePosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        List<Map.Entry<Pos, String>> seen = Perceiver.exposedBlocks(level, p.blockPosition(), eyes, radius, blockId);
        if (!seen.isEmpty()) {
            Pos s = seen.get(0).getKey();
            best = new BlockPos(s.x(), s.y(), s.z());
            bestD2 = Vec3.atCenterOf(best).distanceToSqr(eyes);
        }
        if (ctx.knownBlocks() != null) {
            BlockPos feet = p.blockPosition();
            for (Map.Entry<Pos, String> e : ctx.knownBlocks().nearestN(new Pos(feet.getX(), feet.getY(), feet.getZ()), Integer.MAX_VALUE, 0)) {
                if (!blockId.test(e.getValue())) continue;
                BlockPos pos = new BlockPos(e.getKey().x(), e.getKey().y(), e.getKey().z());
                double d2 = Vec3.atCenterOf(pos).distanceToSqr(eyes);
                if (d2 > (double) radius * radius || d2 >= bestD2 || !level.isLoaded(pos)) continue;
                if (blockId.test(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath())) {
                    best = pos;
                    bestD2 = d2;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Blocks within {@code radius} matching {@code match} that the bot can see from its eyes, nearest first. */
    public static List<BlockPos> visibleStates(AiPlayer p, Predicate<BlockState> match, int radius) {
        return Perceiver.exposedStates(p.level(), p.blockPosition(), p.getEyePosition(), radius, match);
    }

    /** The nearest living {@code type} other than the bot within {@code radius} that passes {@code match}. */
    public static <T extends Entity> Optional<T> nearestEntity(AiPlayer p, Class<T> type, Predicate<T> match, double radius) {
        return p.level().getEntitiesOfClass(type, p.getBoundingBox().inflate(radius),
                        e -> e != p && e.isAlive() && e.distanceTo(p) <= radius && match.test(e))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
    }
}
