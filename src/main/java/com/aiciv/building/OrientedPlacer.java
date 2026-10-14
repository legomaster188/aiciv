package com.aiciv.building;

import com.aiciv.actions.BotContext;
import com.aiciv.bot.AiPlayer;
import com.aiciv.building.PlacementRule.Kind;
import com.aiciv.building.PlacementRule.Need;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.BlockUse;
import com.aiciv.world.Riding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places one block whose orientation depends on where the placer stands and looks (repeaters, pistons, observers,
 * hoppers, levers, logs ...) the way a player would: finds a spot from which the look that vanilla turns into the wanted
 * orientation reaches a face to lean on, walks there, places, checks, and sets delays and modes with right-clicks.
 */
final class OrientedPlacer {
    enum State { RUNNING, DONE, FAILED }

    record Status(State state, String reason) {
        static final Status RUNNING = new Status(State.RUNNING, "");
        static final Status DONE = new Status(State.DONE, "");

        static Status failed(String reason) {
            return new Status(State.FAILED, reason);
        }
    }

    private enum Phase { CHOOSE, WALK, PLACE, BREAK, ADJUST }

    private record Spot(BlockPos feet, Direction side) {}

    static final int SEARCH = 4;
    /** Properties the placement itself decides; the rest are set afterwards by clicking. */
    private static final Set<String> PLACED_PROPS = Set.of("facing", "face", "axis");
    private static final double REACH = 4.3;
    private static final double SEEN = 0.55;
    /** How far from the middle of a compass sector the look may point, so a walk that ends a little off still lands in it. */
    private static final double SECTOR_MARGIN = 35;
    private static final double DOMINANCE = 1.1;
    private static final int PACE = 4;
    private static final int MAX_CLICKS = 8;
    private static final int REFUSED_TICKS = 20;

    private final BlockPos target;
    private final Block block;
    private final String id;
    private final Map<String, String> props;
    private final Set<BlockPos> planCells;
    private final Need need;
    private final List<Direction> supports;

    private Phase phase = Phase.CHOOSE;
    private List<Spot> spots = List.of();
    private int spotIndex;
    private boolean walking;
    private int walkTries;
    private int refused;
    private int cooldown;
    private int clicks;
    private String afterBreak;

    OrientedPlacer(BlockPos target, Block block, String id, Map<String, String> props, Set<BlockPos> planCells) {
        this.target = target;
        this.block = block;
        this.id = id;
        this.props = props;
        this.planCells = planCells;
        Kind kind = PlacementRule.of(id);
        this.need = PlacementRule.need(kind, props);
        this.supports = PlacementRule.supports(kind, props);
    }

    Status tick(BotContext ctx) {
        AiPlayer p = ctx.player();
        ServerLevel level = p.level();
        return switch (phase) {
            case CHOOSE -> choose(p, level);
            case WALK -> walk(ctx, p);
            case PLACE -> place(ctx, p, level);
            case BREAK -> breakAgain(ctx);
            case ADJUST -> adjust(ctx, p, level);
        };
    }

    void cancel(BotContext ctx) {
        ctx.breaker().cancel();
        ctx.navigator().stop();
    }

    // ---------------------------------------------------------------- where to stand

    private Status choose(AiPlayer p, ServerLevel level) {
        spots = spots(p, level);
        if (spots.isEmpty()) return Status.failed("can't place " + spec() + " from anywhere nearby (" + lookHint() + ")");
        spotIndex = 0;
        startWalk();
        return Status.RUNNING;
    }

    private String spec() {
        return new BlockSpec(id, props).text();
    }

    private String lookHint() {
        if (need.horizontalLook() != null) return "a player would have to look " + need.horizontalLook().getName();
        if (need.nearestLook() != null) return "a player would have to look " + need.nearestLook().getName();
        if (need.supportSide() != null) return "a player would have to lean on the " + need.supportSide().getName() + " side";
        return "a player would have to see a block to lean on";
    }

    private List<Spot> spots(AiPlayer p, ServerLevel level) {
        List<Spot> out = new ArrayList<>();
        Vec3 here = p.position();
        for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (feet.equals(target) || feet.above().equals(target) || planCells.contains(feet) || planCells.contains(feet.above())) continue;
                    if (!canStand(level, feet)) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    Direction side = firstSupport(level, p, eye);
                    if (side != null) out.add(new Spot(feet, side));
                }
            }
        }
        out.sort(Comparator.comparingDouble((Spot s) -> cost(s, here)));
        return out;
    }

    /** Distance for the bot to walk, with a penalty for crowding the target: a body at its edge may block the placement. */
    private double cost(Spot s, Vec3 here) {
        double walk = Vec3.atBottomCenterOf(s.feet()).distanceTo(here);
        boolean crowding = Math.abs(s.feet().getX() - target.getX()) <= 1 && Math.abs(s.feet().getZ() - target.getZ()) <= 1;
        return walk + (crowding ? 3 : 0);
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        for (BlockPos cell : new BlockPos[] {feet, feet.above()}) {
            BlockState s = level.getBlockState(cell);
            if (!s.getCollisionShape(level, cell).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /** The first side to lean on from which the look from {@code eye} gives the wanted orientation; null when none does. */
    private Direction firstSupport(ServerLevel level, AiPlayer p, Vec3 eye) {
        List<Direction> sides = need.supportSide() != null ? supports : List.of(Direction.values());
        for (Direction side : sides) {
            if (level.getBlockState(target.relative(side)).canBeReplaced()) continue;
            Vec3 hit = Vec3.atCenterOf(target).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
            if (eye.distanceTo(hit) > REACH || !satisfies(eye, hit) || !sees(level, p, eye, side)) continue;
            return side;
        }
        return null;
    }

    private boolean satisfies(Vec3 eye, Vec3 hit) {
        Vec3 v = hit.subtract(eye);
        if (need.horizontalLook() != null) {
            if (Math.hypot(v.x, v.z) < 0.3) return false;
            float yaw = Riding.yawTo(v.x, v.z);
            Direction look = Direction.fromYRot(yaw);
            if (look != need.horizontalLook() || Math.abs(Mth.wrapDegrees(yaw - look.toYRot())) > SECTOR_MARGIN) return false;
        }
        if (need.nearestLook() != null) {
            Direction nearest = Direction.getApproximateNearest(v);
            if (nearest != need.nearestLook()) return false;
            double[] abs = {Math.abs(v.x), Math.abs(v.y), Math.abs(v.z)};
            int axis = nearest.getAxis().ordinal();
            double other = 0;
            for (int i = 0; i < 3; i++) if (i != axis) other = Math.max(other, abs[i]);
            if (abs[axis] < DOMINANCE * other) return false;
        }
        return true;
    }

    /** The placer's own sight test, from a hypothetical spot: the neighbour's face next to the target is not behind anything. */
    private boolean sees(ServerLevel level, AiPlayer p, Vec3 eye, Direction side) {
        Vec3 aim = Vec3.atCenterOf(target).add(side.getStepX() * SEEN, side.getStepY() * SEEN, side.getStepZ() * SEEN);
        BlockHitResult ray = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return ray.getType() == HitResult.Type.BLOCK && (ray.getBlockPos().equals(target.relative(side)) || ray.getBlockPos().equals(target));
    }

    // ---------------------------------------------------------------- walking, placing

    private void startWalk() {
        phase = Phase.WALK;
        walking = false;
        walkTries = 0;
    }

    private boolean atSpot(AiPlayer p, Spot s) {
        Vec3 here = p.position();
        return Math.hypot(here.x - (s.feet().getX() + 0.5), here.z - (s.feet().getZ() + 0.5)) <= 0.4 && Math.abs(here.y - s.feet().getY()) < 1.0;
    }

    private Status walk(BotContext ctx, AiPlayer p) {
        Spot spot = spots.get(spotIndex);
        NavStatus nav = ctx.navigator().status();
        if (atSpot(p, spot) || (walking && nav.state() == NavStatus.State.ARRIVED)) {
            ctx.navigator().stop();
            phase = Phase.PLACE;
            refused = 0;
            return Status.RUNNING;
        }
        if (walking && nav.state() == NavStatus.State.RUNNING) return Status.RUNNING;
        if (walking && nav.state() == NavStatus.State.FAILED) return nextSpot();
        if (++walkTries > 3) return nextSpot();
        ctx.navigator().goTo(new NearGoal(spot.feet().getX() + 0.5, spot.feet().getY(), spot.feet().getZ() + 0.5, 0.6));
        walking = true;
        return Status.RUNNING;
    }

    private Status nextSpot() {
        if (++spotIndex >= spots.size()) return Status.failed("can't place " + spec() + " from anywhere nearby (could not walk to a spot where " + lookHint() + ")");
        startWalk();
        return Status.RUNNING;
    }

    private Status place(BotContext ctx, AiPlayer p, ServerLevel level) {
        BlockState now = level.getBlockState(target);
        if (now.is(block)) return verify(ctx, now);
        Item item = block.asItem();
        switch (BlockPlacer.placeAgainst(p, target, spots.get(spotIndex).side(), s -> s.is(item))) {
            case PLACED:
                return verify(ctx, level.getBlockState(target));
            case NO_ITEM:
                return Status.failed("ran out of " + id);
            case OCCUPIED:
                return Status.failed("could not place " + spec() + " at " + coords() + " - the cell is taken");
            case REFUSED:
                if (++refused >= REFUSED_TICKS) return Status.failed("could not place " + spec() + " at " + coords() + " - something is in the way");
                return Status.RUNNING;
            default:
                // Not exactly as the spot was worked out for (reach, sight or support): try the next one.
                return nextSpot();
        }
    }

    private Status verify(BotContext ctx, BlockState state) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!PLACED_PROPS.contains(e.getKey())) continue;
            Property<?> property = block.getStateDefinition().getProperty(e.getKey());
            if (property == null) continue;
            String got = valueName(state, property);
            if (got.equals(e.getValue())) continue;
            // The block is the bot's own, so it may take it down again.
            afterBreak = "placed " + id + " with " + e.getKey() + "=" + got + " instead of " + e.getValue();
            ctx.breaker().start(target);
            phase = Phase.BREAK;
            return Status.RUNNING;
        }
        phase = Phase.ADJUST;
        cooldown = 0;
        return Status.RUNNING;
    }

    private Status breakAgain(BotContext ctx) {
        BlockBreaker.Status s = ctx.breaker().tick();
        if (s.state() == BlockBreaker.Status.State.RUNNING) return Status.RUNNING;
        return Status.failed(afterBreak);
    }

    // ---------------------------------------------------------------- delays and modes

    private Status adjust(BotContext ctx, AiPlayer p, ServerLevel level) {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        BlockState state = level.getBlockState(target);
        if (!state.is(block)) return Status.failed(id + " at " + coords() + " changed while it was being set");
        if (clicksLeft(state) == 0) return Status.DONE;
        if (clicks >= MAX_CLICKS) return Status.failed("could not set " + spec() + " at " + coords() + " after " + clicks + " clicks");
        BlockUse.withFreeHand(p);
        switch (BlockUse.click(p, target)) {
            case OUT_OF_REACH:
                return Status.failed("too far from " + id + " at " + coords() + " to set it");
            case NOT_VISIBLE:
                return Status.failed("can't see " + id + " at " + coords() + " to set it");
            case NO_EFFECT:
                return Status.failed("clicking " + id + " at " + coords() + " did nothing");
            default:
                clicks++;
                cooldown = PACE - 1;
                return Status.RUNNING;
        }
    }

    /** Right-clicks still needed: a repeater adds 1 delay per click (4 wraps to 1), a comparator or lever toggles. */
    private int clicksLeft(BlockState state) {
        int left = 0;
        for (Map.Entry<String, String> e : props.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(e.getKey());
            if (property == null || PLACED_PROPS.contains(e.getKey())) continue;
            String got = valueName(state, property), want = e.getValue();
            if (got.equals(want)) continue;
            left += e.getKey().equals("delay") ? Math.floorMod(Integer.parseInt(want) - Integer.parseInt(got), 4) : 1;
        }
        return left;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String valueName(BlockState state, Property property) {
        return property.getName(state.getValue(property));
    }

    private String coords() {
        return target.getX() + " " + target.getY() + " " + target.getZ();
    }
}
