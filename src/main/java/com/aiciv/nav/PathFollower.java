package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import com.aiciv.world.BlockBreaker;
import com.aiciv.world.BlockPlacer;
import com.aiciv.world.InventoryOps;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Steers a fake player along path steps: walks, digs, bridges, pillars and swims by setting input each tick. */
public final class PathFollower {
    public enum Step { MOVING, ARRIVED, STUCK }

    static final double REACH = 0.35;
    static final double SWIM_REACH = 0.5;
    static final int STUCK_TICKS = 60;
    /** Feet this far above the block being replaced clear it, so a throwaway can go where the bot stood. */
    static final double PILLAR_CLEARANCE = 1.05;
    /** The bot is already over the next cell when it is within half a cell of the near edge plus this margin. */
    static final double BRIDGE_EDGE = 1.2;

    private final List<PathStep> steps;
    private final BlockBreaker breaker;
    private int index;
    private double best = Double.MAX_VALUE;
    private int idle;

    public PathFollower(List<PathStep> steps, BlockBreaker breaker) {
        this.steps = steps;
        this.breaker = breaker;
    }

    public Step tick(AiPlayer p) {
        while (index < steps.size()) {
            PathStep step = steps.get(index);
            Pos n = step.to();
            double dx = n.x() + 0.5 - p.getX();
            double dz = n.z() + 0.5 - p.getZ();
            double dy = n.y() - p.getY();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (arrived(step, p, horiz, dy)) {
                index++;
                best = Double.MAX_VALUE;
                idle = 0;
                continue;
            }

            BlockPos dig = nextBreak(p, step);
            if (dig != null) {
                p.stopMoving();
                if (!breaker.busy()) breaker.start(dig);
                if (breaker.tick().state() == BlockBreaker.Status.State.FAILED) return stuck(p);
                idle = 0; // a long dig is not "stuck"
                return Step.MOVING;
            }

            if (step.move() == Move.PILLAR) {
                p.xxa = 0.0F;
                p.zza = 0.0F;
                p.setXRot(90.0F);
                p.setJumping(true);
                if (p.getY() >= step.place().y() + PILLAR_CLEARANCE) {
                    if (!placed(p, step)) return stuck(p);
                    idle = 0;
                }
                return countIdle(p, horiz + Math.abs(dy));
            }
            if (step.move() == Move.BRIDGE && horiz <= BRIDGE_EDGE
                    && p.level().getBlockState(toPos(step.place())).canBeReplaced()) {
                p.stopMoving();
                if (!placed(p, step)) return stuck(p);
                idle = 0;
                return Step.MOVING;
            }

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.yBodyRot = yaw;
            p.xxa = 0.0F;
            p.zza = 1.0F;
            boolean wet = p.isInWater();
            p.setJumping((wet && ((p.isUnderWater() && dy > -0.5) || dy > 0.5 || p.horizontalCollision))
                    || (p.onGround() && (dy > 0.5 || p.horizontalCollision)));
            return countIdle(p, horiz + Math.abs(dy));
        }
        p.stopMoving();
        return Step.ARRIVED;
    }

    private boolean arrived(PathStep step, AiPlayer p, double horiz, double dy) {
        return switch (step.move()) {
            // Mid-jump the feet pass the target height before the block is there to land on.
            case PILLAR -> horiz < REACH && p.onGround() && Math.abs(dy) < 0.2;
            // Swimmers bob around the surface.
            case SWIM -> horiz < SWIM_REACH && Math.abs(dy) < 1.0;
            default -> horiz < REACH && Math.abs(dy) < 0.6;
        };
    }

    /** First block of this step that still blocks the way, or null. */
    private BlockPos nextBreak(AiPlayer p, PathStep step) {
        Level level = p.level();
        for (Pos b : step.breaks()) {
            BlockPos pos = toPos(b);
            BlockState s = level.getBlockState(pos);
            if (!s.getCollisionShape(level, pos).isEmpty()) return pos;
        }
        return null;
    }

    private boolean placed(AiPlayer p, PathStep step) {
        BlockPlacer.PlaceResult r = BlockPlacer.place(p, toPos(step.place()), InventoryOps::isThrowaway);
        return r == BlockPlacer.PlaceResult.PLACED || r == BlockPlacer.PlaceResult.OCCUPIED;
    }

    private Step countIdle(AiPlayer p, double remaining) {
        if (remaining < best - 0.1) {
            best = remaining;
            idle = 0;
        } else if (++idle >= STUCK_TICKS) {
            return stuck(p);
        }
        return Step.MOVING;
    }

    private Step stuck(AiPlayer p) {
        breaker.cancel();
        p.stopMoving();
        return Step.STUCK;
    }

    private static BlockPos toPos(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }
}
