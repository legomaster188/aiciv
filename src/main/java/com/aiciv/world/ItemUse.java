package com.aiciv.world;

import com.aiciv.bot.AiPlayer;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Using the held item in the air (throwing, drinking, firing a rocket) while looking a chosen way. */
public final class ItemUse {
    /** Vanilla spawns thrown items and arrows this far below the eyes. */
    static final double LAUNCH_DROP = 0.1;

    private ItemUse() {}

    /** Selects a matching item into the main hand; false when the bot carries none. */
    public static boolean select(AiPlayer p, Predicate<ItemStack> item) {
        return InventoryOps.selectBest(p, s -> item.test(s) ? 1 : 0);
    }

    /** Looks along {@code yaw} and {@code pitch}, then right-clicks the air with the main hand. */
    public static InteractionResult useFacing(AiPlayer p, float yaw, float pitch) {
        Riding.faceYaw(p, yaw);
        p.setXRot(pitch);
        return p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    /** {yaw, pitch} that throws a {@code kind} projectile from the eyes through {@code target}; empty when out of range. */
    public static Optional<float[]> aimThrow(AiPlayer p, Ballistics.Kind kind, Vec3 target) {
        return aimThrow(p, kind, kind.speed(), target);
    }

    /** As above at a launch speed of {@code speed} (an arrow from a partly drawn bow is slower). */
    public static Optional<float[]> aimThrow(AiPlayer p, Ballistics.Kind kind, double speed, Vec3 target) {
        Vec3 eyes = p.getEyePosition();
        double dx = target.x - eyes.x, dz = target.z - eyes.z;
        OptionalDouble pitch = Ballistics.pitch(kind, speed, Math.sqrt(dx * dx + dz * dz), target.y - (eyes.y - LAUNCH_DROP));
        if (pitch.isEmpty()) return Optional.empty();
        return Optional.of(new float[] {Riding.yawTo(dx, dz), (float) pitch.getAsDouble()});
    }
}
