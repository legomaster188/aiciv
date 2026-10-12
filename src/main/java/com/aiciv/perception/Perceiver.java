package com.aiciv.perception;

import com.aiciv.bot.AiPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Builds a bot's snapshot from the world. Fair: only blocks touching air or water are seen. */
public final class Perceiver {
    static final int BLOCK_RADIUS = 24;
    static final int ENTITY_RADIUS = 32;
    private static final Set<String> UTILITY = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker", "chest", "trapped_chest", "barrel",
            "anvil", "enchanting_table", "brewing_stand");

    private Perceiver() {}

    public static SnapshotData capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi) {
        ServerLevel level = bot.level();
        BlockPos origin = bot.blockPosition();
        long clock = level.getOverworldClockTime();
        ItemStack held = bot.getInventory().getSelectedItem();
        return new SnapshotData(
                bot.getScoreboardName(), origin.getX(), origin.getY(), origin.getZ(),
                Math.round(bot.getHealth()), Math.round(bot.getMaxHealth()), bot.getFoodData().getFoodLevel(),
                GameTime.day(clock), GameTime.phase(clock), stripNamespace(level.getBiome(origin).getRegisteredName()),
                held.isEmpty() ? "nothing" : itemId(held), inventory(bot),
                blocks(level, origin), players(bot, isAi), mobs(bot),
                currentAction, lastResult);
    }

    static List<String> inventory(AiPlayer bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) counts.merge(itemId(s), s.getCount(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        counts.forEach((id, n) -> out.add(n > 1 ? id + " x" + n : id));
        return out;
    }

    static List<Sighting> blocks(ServerLevel level, BlockPos origin) {
        // label -> {count, nearest distance squared, dx, dz}
        Map<String, int[]> seen = new HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int r = BLOCK_RADIUS, r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    String label = label(s);
                    if (label == null) continue;
                    boolean fluid = label.equals("water") || label.equals("lava");
                    if (!fluid && !exposed(level, p)) continue;
                    int[] agg = seen.computeIfAbsent(label, k -> new int[] {0, Integer.MAX_VALUE, 0, 0});
                    agg[0]++;
                    if (d2 < agg[1]) {
                        agg[1] = d2;
                        agg[2] = dx;
                        agg[3] = dz;
                    }
                }
            }
        }
        List<Sighting> out = new ArrayList<>();
        seen.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue()[1]))
                .forEach(e -> out.add(new Sighting(e.getKey(), e.getValue()[0],
                        (int) Math.round(Math.sqrt(e.getValue()[1])), Compass.direction(e.getValue()[2], e.getValue()[3]))));
        return out;
    }

    static String label(BlockState s) {
        if (s.is(Blocks.WATER)) return "water";
        if (s.is(Blocks.LAVA)) return "lava";
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.endsWith("_ore") || id.equals("ancient_debris") || id.endsWith("_log") || id.endsWith("_bed") || UTILITY.contains(id)) {
            return id;
        }
        return null;
    }

    static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.isLoaded(n)) continue;
            BlockState s = level.getBlockState(n);
            if (s.isAir() || s.getFluidState().is(FluidTags.WATER)) return true;
        }
        return false;
    }

    static List<Sighting> players(AiPlayer bot, Predicate<ServerPlayer> isAi) {
        List<Sighting> out = new ArrayList<>();
        for (ServerPlayer other : bot.level().players()) {
            if (other == bot || other.isSpectator()) continue;
            double d = other.distanceTo(bot);
            if (d > ENTITY_RADIUS) continue;
            String tag = isAi.test(other) ? " [AI]" : " [human]";
            out.add(new Sighting(other.getScoreboardName() + tag, 1, (int) Math.round(d),
                    Compass.direction(other.getX() - bot.getX(), other.getZ() - bot.getZ())));
        }
        out.sort(Comparator.comparingInt(Sighting::distance));
        return out;
    }

    static List<Sighting> mobs(AiPlayer bot) {
        AABB box = bot.getBoundingBox().inflate(ENTITY_RADIUS);
        // id -> {count, nearest distance, dx, dz}
        Map<String, double[]> agg = new HashMap<>();
        for (LivingEntity e : bot.level().getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof Player) && e.isAlive())) {
            double d = e.distanceTo(bot);
            if (d > ENTITY_RADIUS) continue;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            double[] a = agg.computeIfAbsent(id, k -> new double[] {0, Double.MAX_VALUE, 0, 0});
            a[0]++;
            if (d < a[1]) {
                a[1] = d;
                a[2] = e.getX() - bot.getX();
                a[3] = e.getZ() - bot.getZ();
            }
        }
        List<Sighting> out = new ArrayList<>();
        agg.forEach((id, a) -> out.add(new Sighting(id, (int) a[0], (int) Math.round(a[1]), Compass.direction(a[2], a[3]))));
        out.sort(Comparator.comparingInt(Sighting::distance));
        return out;
    }

    static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
