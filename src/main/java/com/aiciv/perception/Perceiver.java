package com.aiciv.perception;

import com.aiciv.actions.Attachments;
import com.aiciv.bot.AiPlayer;
import com.aiciv.dimension.Portals;
import com.aiciv.modules.ToolModules;
import com.aiciv.nav.Pos;
import com.aiciv.world.BlockNames;
import com.aiciv.world.KnownBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Builds a bot's snapshot from the world. Fair: only blocks touching air or water are seen. */
public final class Perceiver {
    static final int BLOCK_RADIUS = 24;
    static final int ENTITY_RADIUS = 32;
    static final int KNOWN_CAPACITY = 128;
    private static final int REMEMBERED_MAX = 8;
    /** Line-of-sight rays one scan may cast; candidates beyond that stay unseen. */
    private static final int MAX_RAYS = 400;
    /**
     * The snapshot casts rays at no more than this many of the nearest blocks of each kind, so a fortress wall or a field
     * of logs cannot use up the ray budget before the lava, the portal or the ore a little farther away is looked at.
     */
    static final int RAYS_PER_LABEL = 24;
    private static final Set<String> UTILITY = Set.of(
            "crafting_table", "furnace", "blast_furnace", "smoker", "chest", "trapped_chest", "barrel",
            "anvil", "enchanting_table", "brewing_stand",
            "nether_portal", "end_portal", "end_gateway", "end_portal_frame", "spawner", "obsidian", "crying_obsidian",
            "nether_bricks", "rail", "powered_rail", "detector_rail", "activator_rail", "smithing_table", "stonecutter",
            "grindstone", "loom", "cartography_table", "lodestone");

    private Perceiver() {}

    /** For callers that keep no memory between snapshots. */
    public static SnapshotData capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi) {
        return capture(bot, currentAction, lastResult, isAi, new KnownBlocks(KNOWN_CAPACITY));
    }

    /** Also records what it sees into {@code known} and forgets remembered blocks that have changed. */
    public static SnapshotData capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi,
                                       KnownBlocks known) {
        return capture(bot, currentAction, lastResult, isAi, known, new Attachments());
    }

    /** As above, with the modules' sections read from the bot's {@code attachments}. */
    public static SnapshotData capture(AiPlayer bot, String currentAction, String lastResult, Predicate<ServerPlayer> isAi,
                                       KnownBlocks known, Attachments attachments) {
        ServerLevel level = bot.level();
        BlockPos origin = bot.blockPosition();
        long clock = level.getOverworldClockTime();
        ItemStack held = bot.getInventory().getSelectedItem();
        known.enterDimension(Portals.key(level));
        List<Sighting> blocks = blocks(level, origin, bot.getEyePosition(), known);
        return new SnapshotData(
                bot.getScoreboardName(), origin.getX(), origin.getY(), origin.getZ(),
                Math.round(bot.getHealth()), Math.round(bot.getMaxHealth()), bot.getFoodData().getFoodLevel(),
                GameTime.day(clock), GameTime.phase(clock), stripNamespace(level.getBiome(origin).getRegisteredName()),
                held.isEmpty() ? "nothing" : itemId(held), equipment(bot), inventory(bot),
                blocks, players(bot, isAi), mobs(bot), remembered(known, origin),
                currentAction, lastResult, bot.experienceLevel, ToolModules.sections(bot, attachments));
    }

    static String equipment(AiPlayer bot) {
        List<String> worn = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack s = bot.getItemBySlot(slot);
            if (!s.isEmpty()) worn.add(itemId(s));
        }
        return worn.isEmpty() ? "none" : String.join(", ", worn);
    }

    /** The nearest known blocks beyond what the scan can see, e.g. "iron_ore at 120 34 -50 (60m NE)". */
    static List<String> remembered(KnownBlocks known, BlockPos origin) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Pos, String> e : known.nearestN(new Pos(origin.getX(), origin.getY(), origin.getZ()), REMEMBERED_MAX, BLOCK_RADIUS)) {
            Pos p = e.getKey();
            int dx = p.x() - origin.getX(), dy = p.y() - origin.getY(), dz = p.z() - origin.getZ();
            out.add(String.format(Locale.ROOT, "%s at %d %d %d (%dm %s)", e.getValue(), p.x(), p.y(), p.z(),
                    Math.round(Math.sqrt((double) dx * dx + dy * dy + dz * dz)), Compass.direction(dx, dz)));
        }
        return out;
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

    /**
     * Every block within {@code radius} whose id (path only) passes {@code label} and that the bot could see from its
     * head (about 1.62 above the feet), nearest first.
     */
    public static List<Map.Entry<Pos, String>> exposedBlocks(ServerLevel level, BlockPos origin, int radius, Predicate<String> label) {
        return exposedBlocks(level, origin, eyeAbove(origin), radius, label);
    }

    public static List<Map.Entry<Pos, String>> exposedBlocks(ServerLevel level, BlockPos origin, Vec3 eye, int radius,
                                                             Predicate<String> label) {
        List<Map.Entry<Pos, String>> out = new ArrayList<>();
        scan(level, origin, eye, radius, s -> {
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            return label.test(id) ? id : null;
        }, Integer.MAX_VALUE, (id, p, d2, dx, dy, dz) -> out.add(Map.entry(new Pos(p.getX(), p.getY(), p.getZ()), id)));
        return out; // the scan reports nearest first
    }

    /** Every block state within {@code radius} that passes {@code match} and that a ray from {@code eye} reaches, nearest first. */
    public static List<BlockPos> exposedStates(ServerLevel level, BlockPos origin, Vec3 eye, int radius, Predicate<BlockState> match) {
        List<BlockPos> out = new ArrayList<>();
        scan(level, origin, eye, radius, s -> match.test(s) ? "" : null, Integer.MAX_VALUE, (label, p, d2, dx, dy, dz) -> out.add(p));
        return out;
    }

    private static Vec3 eyeAbove(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
    }

    private interface Visitor {
        void visit(String label, BlockPos pos, int d2, int dx, int dy, int dz);
    }

    /** {@code seeThrough}: the block has no visual shape (portals, rails), so a sight ray passes through it instead of ending there. */
    private record Candidate(String label, BlockPos pos, int d2, int dx, int dy, int dz, List<Direction> faces, boolean fluid,
                             boolean seeThrough) {}

    /**
     * Visits, nearest first, blocks in a sphere that the labeller names, that touch air (or water), and that a ray from
     * {@code eye} actually reaches. Shared by the snapshot and by lookups, so a bot never knows about hidden blocks. Only
     * the {@code perLabel} nearest candidates of each label are looked at.
     */
    private static void scan(ServerLevel level, BlockPos origin, Vec3 eye, int r, Function<BlockState, String> labeller,
                             int perLabel, Visitor visitor) {
        List<Candidate> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r2) continue;
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    String label = labeller.apply(s);
                    if (label == null) continue;
                    boolean fluid = s.getBlock() instanceof LiquidBlock;
                    List<Direction> faces = exposedFaces(level, p, !fluid);
                    if (faces.isEmpty()) continue;
                    boolean seeThrough = !fluid && s.getVisualShape(level, p, CollisionContext.empty()).isEmpty();
                    candidates.add(new Candidate(label, p.immutable(), d2, dx, dy, dz, faces, fluid, seeThrough));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::d2));
        int[] rays = {0};
        Map<String, Integer> perKind = new HashMap<>();
        for (Candidate c : candidates) {
            if (rays[0] >= MAX_RAYS) break;
            if (perKind.merge(c.label(), 1, Integer::sum) > perLabel) continue;
            if (visible(level, eye, c, rays)) visitor.visit(c.label(), c.pos(), c.d2(), c.dx(), c.dy(), c.dz());
        }
    }

    /** Tries the block centre, then the centre of up to two exposed faces, stopping at the first ray that arrives. */
    private static boolean visible(ServerLevel level, Vec3 eye, Candidate c, int[] rays) {
        ClipContext.Fluid fluids = c.fluid() ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
        Vec3 centre = Vec3.atCenterOf(c.pos());
        if (reaches(level, eye, centre, c, fluids, rays)) return true;
        for (int i = 0; i < Math.min(2, c.faces().size()); i++) {
            Direction d = c.faces().get(i);
            // Aim a little inside the face so the ray ends within the block rather than on its surface.
            Vec3 target = centre.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
            if (reaches(level, eye, target, c, fluids, rays)) return true;
        }
        return false;
    }

    private static boolean reaches(ServerLevel level, Vec3 eye, Vec3 target, Candidate c, ClipContext.Fluid fluids, int[] rays) {
        rays[0]++;
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, fluids, CollisionContext.empty()));
        // A ray to a point inside a see-through block arrives when nothing on the way stops it.
        if (c.seeThrough()) return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(c.pos());
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(c.pos());
    }

    static List<Sighting> blocks(ServerLevel level, BlockPos origin, Vec3 eye, KnownBlocks known) {
        // label -> {count, nearest distance squared, dx, dz}
        Map<String, int[]> seen = new HashMap<>();
        long tick = level.getGameTime();
        scan(level, origin, eye, BLOCK_RADIUS, Perceiver::label, RAYS_PER_LABEL, (label, p, d2, dx, dy, dz) -> {
            int[] agg = seen.computeIfAbsent(label, k -> new int[] {0, Integer.MAX_VALUE, 0, 0});
            agg[0]++;
            if (d2 < agg[1]) {
                agg[1] = d2;
                agg[2] = dx;
                agg[3] = dz;
            }
            if (!label.equals("water") && !label.equals("lava")) known.see(label, new Pos(p.getX(), p.getY(), p.getZ()), tick);
        });
        forgetChanged(level, origin, known);
        List<Sighting> out = new ArrayList<>();
        seen.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue()[1]))
                .forEach(e -> out.add(new Sighting(e.getKey(), e.getValue()[0],
                        (int) Math.round(Math.sqrt(e.getValue()[1])), Compass.direction(e.getValue()[2], e.getValue()[3]))));
        return out;
    }

    /** Drops remembered blocks inside the scan radius that are no longer what we remembered (mined, burnt, replaced). */
    private static void forgetChanged(ServerLevel level, BlockPos origin, KnownBlocks known) {
        long r2 = (long) BLOCK_RADIUS * BLOCK_RADIUS;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Map.Entry<Pos, String> e : known.nearestN(new Pos(origin.getX(), origin.getY(), origin.getZ()), known.size(), 0)) {
            Pos k = e.getKey();
            long dx = k.x() - origin.getX(), dy = k.y() - origin.getY(), dz = k.z() - origin.getZ();
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            p.set(k.x(), k.y(), k.z());
            if (!level.isLoaded(p)) continue;
            if (!e.getValue().equals(label(level.getBlockState(p)))) known.gone(k);
        }
    }

    static String label(BlockState s) {
        if (s.is(Blocks.WATER)) return "water";
        if (s.is(Blocks.LAVA)) return "lava";
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.endsWith("_ore") || id.equals("ancient_debris") || BlockNames.isLog(id) || id.endsWith("_bed") || UTILITY.contains(id)) {
            return id;
        }
        return null;
    }

    /** Faces beside air; solid blocks also count a face beside water, fluids only beside air. */
    static List<Direction> exposedFaces(ServerLevel level, BlockPos p, boolean waterCounts) {
        List<Direction> faces = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.isLoaded(n)) continue;
            BlockState s = level.getBlockState(n);
            if (s.isAir() || (waterCounts && s.getFluidState().is(FluidTags.WATER))) faces.add(d);
        }
        return faces;
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
