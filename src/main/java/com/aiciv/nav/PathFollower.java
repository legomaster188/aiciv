package com.aiciv.nav;

import com.aiciv.bot.AiPlayer;
import java.util.List;

/** Steers a fake player along a path by setting its facing and movement input each tick. */
public final class PathFollower {
    public enum Step { MOVING, ARRIVED, STUCK }

    static final double REACH = 0.35;
    static final int STUCK_TICKS = 60;

    private final List<Pos> path;
    private int index;
    private double best = Double.MAX_VALUE;
    private int idle;

    public PathFollower(List<Pos> path) {
        this.path = path;
    }

    public Step tick(AiPlayer p) {
        while (index < path.size()) {
            Pos n = path.get(index);
            double dx = n.x() + 0.5 - p.getX();
            double dz = n.z() + 0.5 - p.getZ();
            double dy = n.y() - p.getY();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < REACH && Math.abs(dy) < 0.6) {
                index++;
                best = Double.MAX_VALUE;
                idle = 0;
                continue;
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.yBodyRot = yaw;
            p.xxa = 0.0F;
            p.zza = 1.0F;
            p.setJumping(p.isInWater() || (p.onGround() && (dy > 0.5 || p.horizontalCollision)));
            double remaining = horiz + Math.abs(dy);
            if (remaining < best - 0.1) {
                best = remaining;
                idle = 0;
            } else if (++idle >= STUCK_TICKS) {
                p.stopMoving();
                return Step.STUCK;
            }
            return Step.MOVING;
        }
        p.stopMoving();
        return Step.ARRIVED;
    }
}
